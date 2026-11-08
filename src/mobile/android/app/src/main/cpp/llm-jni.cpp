// Мост JNI к llama.cpp (v0.9.0, см. dependencies/llama-cpp.properties) для локальной LLM-коррекции ASR.
// Сессия: nativeCreateSession -> nativeLoadModel -> nativeGenerate* -> nativeUnload.
// Отмена: nativeCancel ставит атомарный флаг; decode прерывается abort-колбэком ggml,
// плюс мягкая остановка между токенами. Освобождение контекста/модели только после завершения
// активного вызова (Kotlin сериализует вызовы общим замком, здесь — per-session мьютекс).
#include <jni.h>

#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include <ggml.h>
#include <llama.h>

#define LOG_TAG "llm-jni"

namespace {

struct LlmSession {
    // Сериализация load/generate/unload внутри сессии; nativeCancel флаг не трогает — только atomic.
    std::mutex mutex;
    std::atomic<bool> cancel{false};
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
};

std::mutex g_sessions_mutex;
std::unordered_map<int64_t, std::shared_ptr<LlmSession>> g_sessions;
std::atomic<int64_t> g_next_id{1};

void log_to_android(enum ggml_log_level level, const char *text, void * /* user_data */) {
    int prio = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    __android_log_print(prio, LOG_TAG, "%s", text);
}

void ensure_backend() {
    static std::once_flag flag;
    std::call_once(flag, []() {
        llama_log_set(log_to_android, nullptr);
        llama_backend_init();
    });
}

void throw_java(JNIEnv *env, const char *class_name, const std::string &message) {
    jclass cls = env->FindClass(class_name);
    if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

void throw_runtime(JNIEnv *env, const std::string &message) {
    throw_java(env, "java/lang/RuntimeException", message);
}

void throw_cancelled(JNIEnv *env) {
    throw_java(env, "java/util/concurrent/CancellationException", "Генерация LLM отменена");
}

std::shared_ptr<LlmSession> find_session(JNIEnv *env, int64_t id) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    auto it = g_sessions.find(id);
    if (it == g_sessions.end()) {
        throw_java(env, "java/lang/IllegalStateException", "Сессия LLM не найдена");
        return nullptr;
    }
    return it->second;
}

// GetStringUTFChars отдаёт модифицированный UTF-8 (MUTF-8): для BMP-кириллицы совпадает с UTF-8,
// но суррогатные пары (эмодзи) и NUL ломают токенизатор. Переводим честно: UTF-16 -> UTF-8.
std::string utf16_to_utf8(const jchar *chars, jsize length) {
    std::string out;
    out.reserve(static_cast<size_t>(length));
    auto append = [&out](uint32_t cp) {
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    };
    for (jsize i = 0; i < length; i++) {
        uint32_t cp = static_cast<uint16_t>(chars[i]);
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length) {
            uint32_t low = static_cast<uint16_t>(chars[i + 1]);
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                i++;
            } else {
                cp = 0xFFFD;  // оборванная суррогатная пара
            }
        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
            cp = 0xFFFD;
        }
        append(cp);
    }
    return out;
}

std::string get_utf8(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    jsize length = env->GetStringLength(value);
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string out = utf16_to_utf8(chars, length);
    env->ReleaseStringChars(value, chars);
    return out;
}

// Обратно: UTF-8 -> UTF-16 и NewString (NewStringUTF принимает только MUTF-8 и ломает 4-байтные символы).
jstring make_jstring(JNIEnv *env, const std::string &utf8) {
    std::vector<jchar> out;
    out.reserve(utf8.size());
    size_t i = 0;
    while (i < utf8.size()) {
        uint8_t c = static_cast<uint8_t>(utf8[i]);
        uint32_t cp;
        size_t extra;
        if (c < 0x80) {
            cp = c;
            extra = 0;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < utf8.size()) {
            cp = c & 0x1F;
            extra = 1;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < utf8.size()) {
            cp = c & 0x0F;
            extra = 2;
        } else if ((c & 0xF8) == 0xF0 && i + 3 < utf8.size()) {
            cp = c & 0x07;
            extra = 3;
        } else {
            cp = 0xFFFD;  // некорректный UTF-8 не уходит в Java-строку
            extra = 0;
        }
        for (size_t k = 1; k <= extra; k++) {
            uint8_t cc = static_cast<uint8_t>(utf8[i + k]);
            if ((cc & 0xC0) != 0x80) {
                cp = 0xFFFD;
                extra = 0;
                break;
            }
            cp = (cp << 6) | (cc & 0x3F);
        }
        i += 1 + extra;
        if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) cp = 0xFFFD;
        if (cp < 0x10000) {
            out.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }
    return env->NewString(out.data(), static_cast<jsize>(out.size()));
}

// Отмена загрузки модели: прогресс-колбэк возвращает false -> загрузка прерывается.
bool load_progress(float /* progress */, void *user_data) {
    auto *session = static_cast<LlmSession *>(user_data);
    return !session->cancel.load();
}

// Отмена decode: ggml проверяет колбэк внутри вычисления графа (CPU-only).
bool decode_abort(void *user_data) {
    auto *session = static_cast<LlmSession *>(user_data);
    return session->cancel.load();
}

// temperature <= 0 -> greedy; иначе penalties -> top_k -> top_p -> temp.
llama_sampler *build_sampler(const llama_vocab *vocab, float temperature, float top_p, int32_t top_k,
                             float presence_penalty) {
    llama_sampler_chain_params params = llama_sampler_chain_default_params();
    llama_sampler *chain = llama_sampler_chain_init(params);
    if (chain == nullptr) return nullptr;
    const int32_t n_vocab = llama_vocab_n_tokens(vocab);
    // Штраф подключается только при ненулевом значении: в режиме коррекции он выключен.
    if (presence_penalty > 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(
                n_vocab, 64, 1.0f, 0.0f, presence_penalty));
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(top_k));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(top_p, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
    }
    return chain;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_cfm_catscriptor_native_models_LlamaNativeBridge_nativeCreateSession(JNIEnv *, jobject) {
    ensure_backend();
    auto session = std::make_shared<LlmSession>();
    const int64_t id = g_next_id.fetch_add(1);
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    g_sessions.emplace(id, std::move(session));
    return id;
}

extern "C" JNIEXPORT void JNICALL
Java_org_cfm_catscriptor_native_models_LlamaNativeBridge_nativeLoadModel(
        JNIEnv *env, jobject, jlong id, jstring model_path, jint n_ctx, jint n_threads) {
    auto session = find_session(env, id);
    if (session == nullptr) return;
    std::lock_guard<std::mutex> lock(session->mutex);
    if (session->model != nullptr) return;  // идемпотентность повторной загрузки

    std::string path = get_utf8(env, model_path);
    if (path.empty()) {
        throw_java(env, "java/lang/IllegalArgumentException", "Пустой путь к модели");
        return;
    }

    session->cancel.store(false);

    const auto t_load = std::chrono::steady_clock::now();
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;  // CPU-only
    // Веса читаются в RAM, а не мапятся: на устройствах с вытесняемым page cache mmap даёт
    // ~7 с/токен (повторное чтение 0,85 ГБ с флешки на каждый токен). RAM на целевых
    // телефонах 8–12 ГБ — удержать файл модели в памяти безопасно.
    model_params.load_mode = LLAMA_LOAD_MODE_NONE;
    model_params.progress_callback = load_progress;
    model_params.progress_callback_user_data = session.get();
    llama_model *model = llama_model_load_from_file(path.c_str(), model_params);
    if (model == nullptr) {
        if (session->cancel.load()) {
            throw_cancelled(env);
        } else {
            throw_runtime(env, "Не удалось загрузить GGUF-модель: " + path);
        }
        return;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx > 0 ? static_cast<uint32_t>(n_ctx) : 1024;
    ctx_params.n_batch = 512;
    ctx_params.n_ubatch = 512;
    ctx_params.n_threads = n_threads > 0 ? n_threads : 4;
    ctx_params.n_threads_batch = ctx_params.n_threads;
    ctx_params.abort_callback = decode_abort;
    ctx_params.abort_callback_data = session.get();
    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (ctx == nullptr) {
        llama_model_free(model);
        throw_runtime(env, "Не удалось создать контекст LLM");
        return;
    }

    session->model = model;
    session->ctx = ctx;
    session->vocab = llama_model_get_vocab(model);
    const double load_ms = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - t_load).count();
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "model+context ready: ctx=%d threads=%d load=%.0f ms", n_ctx, n_threads, load_ms);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_cfm_catscriptor_native_models_LlamaNativeBridge_nativeGenerate(
        JNIEnv *env, jobject, jlong id, jstring system_prompt, jstring user_prompt,
        jint max_tokens, jfloat temperature, jfloat top_p, jint top_k, jfloat presence_penalty) {
    auto session = find_session(env, id);
    if (session == nullptr) return nullptr;
    std::lock_guard<std::mutex> lock(session->mutex);
    if (session->ctx == nullptr || session->model == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "Модель LLM не загружена");
        return nullptr;
    }

    session->cancel.store(false);

    const auto t_start = std::chrono::steady_clock::now();

    const std::string system = get_utf8(env, system_prompt);
    const std::string user = get_utf8(env, user_prompt);

    // ChatML через встроенный список шаблонов llama.cpp: tmpl=nullptr => "chatml" (Qwen2.5).
    std::vector<llama_chat_message> messages;
    messages.push_back({"system", system.c_str()});
    messages.push_back({"user", user.c_str()});
    std::vector<char> formatted(2 * (system.size() + user.size()) + 256);
    int32_t needed = llama_chat_apply_template(
            nullptr, messages.data(), messages.size(), true,
            formatted.data(), static_cast<int32_t>(formatted.size()));
    if (needed < 0) {
        throw_runtime(env, "llama_chat_apply_template не поддержал шаблон чата");
        return nullptr;
    }
    if (needed > static_cast<int32_t>(formatted.size())) {
        formatted.resize(static_cast<size_t>(needed) + 1);
        needed = llama_chat_apply_template(
                nullptr, messages.data(), messages.size(), true,
                formatted.data(), static_cast<int32_t>(formatted.size()));
        if (needed < 0 || needed > static_cast<int32_t>(formatted.size())) {
            throw_runtime(env, "Не удалось применить шаблон чата");
            return nullptr;
        }
    }

    const llama_vocab *vocab = session->vocab;
    // parse_special=true: маркеры ChatML (<|im_start|> и т.п.) из шаблона должны стать
    // служебными токенами, а не кусками текста — иначе модель дезориентируется и «печатает»
    // <|im_end|> буквами прямо в ответ (диагностировано на vivo V2538).
    std::vector<llama_token> prompt_tokens(static_cast<size_t>(needed) + 16);
    int32_t n_prompt = llama_tokenize(
            vocab, formatted.data(), needed, prompt_tokens.data(),
            static_cast<int32_t>(prompt_tokens.size()), true, true);
    if (n_prompt < 0) {
        prompt_tokens.resize(static_cast<size_t>(-n_prompt) + 16);
        n_prompt = llama_tokenize(
                vocab, formatted.data(), needed, prompt_tokens.data(),
                static_cast<int32_t>(prompt_tokens.size()), true, true);
    }
    if (n_prompt <= 0) {
        throw_runtime(env, "Не удалось токенизировать промпт");
        return nullptr;
    }
    if (static_cast<uint32_t>(n_prompt) >= llama_n_ctx(session->ctx)) {
        throw_java(env, "java/lang/IllegalArgumentException", "Промпт длиннее контекста LLM");
        return nullptr;
    }

    // Изоляция реплик: очистка памяти между независимыми запросами.
    llama_memory_clear(llama_get_memory(session->ctx), true);

    const int32_t n_batch = static_cast<int32_t>(llama_n_batch(session->ctx));
    for (int32_t i = 0; i < n_prompt; i += n_batch) {
        const int32_t chunk = std::min(n_batch, n_prompt - i);
        llama_batch batch = llama_batch_get_one(prompt_tokens.data() + i, chunk);
        const int ret = llama_decode(session->ctx, batch);
        if (ret == 2) {
            throw_cancelled(env);
            return nullptr;
        }
        if (ret != 0) {
            throw_runtime(env, "llama_decode вернул ошибку " + std::to_string(ret));
            return nullptr;
        }
    }

    llama_sampler *sampler = build_sampler(vocab, temperature, top_p, top_k, presence_penalty);
    if (sampler == nullptr) {
        throw_runtime(env, "Не удалось создать сэмплер");
        return nullptr;
    }

    std::string output;
    output.reserve(256);
    std::vector<char> piece(64);
    int32_t n_generated = 0;
    while (n_generated < max_tokens) {
        if (session->cancel.load()) {
            llama_sampler_free(sampler);
            throw_cancelled(env);
            return nullptr;
        }
        const llama_token token = llama_sampler_sample(sampler, session->ctx, -1);
        llama_sampler_accept(sampler, token);
        if (llama_vocab_is_eog(vocab, token)) break;

        int32_t n_piece = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        if (n_piece < 0) {
            piece.resize(static_cast<size_t>(-n_piece) + 8);
            n_piece = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        }
        if (n_piece > 0) output.append(piece.data(), static_cast<size_t>(n_piece));
        n_generated++;

        llama_token next = token;
        llama_batch batch = llama_batch_get_one(&next, 1);
        const int ret = llama_decode(session->ctx, batch);
        if (ret == 2) {
            llama_sampler_free(sampler);
            throw_cancelled(env);
            return nullptr;
        }
        if (ret == 1) break;  // слот KV исчерпан: обрезаем безопасно, guard отсечёт избыток
        if (ret != 0) {
            llama_sampler_free(sampler);
            throw_runtime(env, "llama_decode вернул ошибку " + std::to_string(ret));
            return nullptr;
        }
    }
    llama_sampler_free(sampler);
    const auto t_end = std::chrono::steady_clock::now();
    const double elapsed_ms = std::chrono::duration<double, std::milli>(t_end - t_start).count();
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "generate: prompt=%d tokens, generated=%d tokens in %.0f ms (%.2f tok/s)",
                        n_prompt, n_generated, elapsed_ms,
                        elapsed_ms > 0.0 ? n_generated * 1000.0 / elapsed_ms : 0.0);
    return make_jstring(env, output);
}

extern "C" JNIEXPORT void JNICALL
Java_org_cfm_catscriptor_native_models_LlamaNativeBridge_nativeCancel(
        JNIEnv *, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(g_sessions_mutex);
    auto it = g_sessions.find(id);
    if (it != g_sessions.end()) it->second->cancel.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_org_cfm_catscriptor_native_models_LlamaNativeBridge_nativeUnload(
        JNIEnv *env, jobject, jlong id) {
    std::shared_ptr<LlmSession> session;
    {
        std::lock_guard<std::mutex> lock(g_sessions_mutex);
        auto it = g_sessions.find(id);
        if (it == g_sessions.end()) return;  // повторное закрытие безопасно
        session = it->second;
        g_sessions.erase(it);
    }
    // Не освобождаем ресурсы, пока активный вызов (generate/load) держит mutex сессии.
    std::lock_guard<std::mutex> lock(session->mutex);
    session->cancel.store(false);
    if (session->ctx != nullptr) {
        llama_free(session->ctx);
        session->ctx = nullptr;
    }
    if (session->model != nullptr) {
        llama_model_free(session->model);
        session->model = nullptr;
    }
}
