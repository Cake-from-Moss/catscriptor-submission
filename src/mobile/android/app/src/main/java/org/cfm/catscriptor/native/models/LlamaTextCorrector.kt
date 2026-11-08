package org.cfm.catscriptor.native.models

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cfm.catscriptor.domain.pipeline.CorrectionGuard
import org.cfm.catscriptor.domain.pipeline.CorrectionPrompt
import org.cfm.catscriptor.domain.pipeline.TextCorrectionBackend
import org.cfm.catscriptor.domain.pipeline.Utterance

/**
 * Локальная LLM-коррекция через llama.cpp (issue #7).
 *
 * Модель загружается лениво при первой реплике и держится до [close]: повторная загрузка GGUF
 * на каждую реплику недопустима. Сессия создаётся до загрузки, а отмена корутины доводится
 * до нативного decode немедленно (watcher на [awaitCancellation]): генерация останавливается
 * между токенами, частичный вывод отбрасывается.
 * [close] вызывать вне UI-потока: он может ожидать завершения активной генерации.
 *
 * Ошибка любой реплики закрывает ресурс (как [SherpaModelResource]): LLM-этап обязан
 * заканчиваться либо корректно, либо ошибкой пайплайна, а не тихим деградированным текстом.
 * Отклонённая [CorrectionGuard] правка возвращает исходный ASR-текст без исключений.
 */
class LlamaTextCorrector internal constructor(
    private val openModel: (checkCancelled: () -> Unit) -> File,
    private val config: LlamaConfig = LlamaConfig(),
    private val bridge: LlamaBridge = LlamaNativeBridge,
    private val guard: CorrectionGuard = CorrectionGuard(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TextCorrectionBackend {

    private val lock = Any()
    private var sessionId: Long = 0
    private var loaded = false
    private var closed = false

    init {
        require(config.threads in 1..8) { "Число потоков должно быть от 1 до 8" }
        require(config.contextSize in 256..8192) { "Контекст LLM вне допустимого диапазона" }
        require(config.maxChunkWords in 1..200) { "Размер фрагмента LLM вне допустимого диапазона" }
        require(config.presencePenalty >= 0f) { "Штраф повтора не может быть отрицательным" }
    }

    override suspend fun correct(utterance: Utterance): String {
        val original = utterance.text
        // Пустой текст не корректируем и модель не трогаем: возвращаем как есть.
        if (original.isBlank()) return original
        return try {
            withContext(dispatcher) {
                val coroutine = currentCoroutineContext()
                coroutine.ensureActive()
                // Сессия создаётся до загрузки: отмена до/во время загрузки доходит
                // до нативного прогресс-колбэка, а не обнаруживается постфактум.
                val session = synchronized(lock) {
                    check(!closed) { "Ресурс корректора уже закрыт" }
                    if (sessionId == 0L) sessionId = bridge.createSession()
                    sessionId
                }
                // Пока выполняется блокирующий нативный вызов, единственный способ доставить
                // отмену — наблюдатель, который по отмене корутины дергает bridge.cancel.
                val cancelWatcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        bridge.cancel(session)
                    }
                }
                try {
                    synchronized(lock) {
                        check(!closed) { "Ресурс корректора уже закрыт" }
                        if (!loaded) {
                            val model = openModel { coroutine.ensureActive() }
                            coroutine.ensureActive()
                            bridge.loadModel(session, model.absolutePath, config.contextSize, config.threads)
                            loaded = true
                        }
                    }
                    // Каждый фрагмент проверяется guard отдельно: отклонённый фрагмент остаётся
                    // текстом ASR и не тянет за собой остальные.
                    val chunks = chunksOf(original)
                    val parts = chunks.mapIndexed { index, chunk ->
                        coroutine.ensureActive()
                        val raw = bridge.generate(
                            sessionId = session,
                            systemPrompt = CorrectionPrompt.SYSTEM,
                            userPrompt = CorrectionPrompt.buildUserMessage(chunk),
                            maxTokens = maxOutputTokensFor(chunk),
                            temperature = config.temperature,
                            topP = config.topP,
                            topK = config.topK,
                            presencePenalty = config.presencePenalty,
                        )
                        coroutine.ensureActive()
                        val candidate = CorrectionPrompt.sanitize(raw)
                        when (val decision = guard.verify(chunk, candidate)) {
                            is CorrectionGuard.Decision.Accept -> decision.text
                            is CorrectionGuard.Decision.UseOriginal -> chunk
                        }.let { text ->
                            // Граница фрагмента — не конец предложения: точку, которую модель
                            // поставила из-за обрыва текста, убираем.
                            if (index < chunks.lastIndex) text.removeSuffix(".") else text
                        }
                    }
                    joinChunks(parts)
                } finally {
                    // При нормальном завершении просто останавливаем наблюдателя; его finally
                    // (bridge.cancel) при этом тоже выполнится — флаг безвреден: следующая
                    // генерация сбрасывает его на старте.
                    cancelWatcher.cancel()
                }
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    /**
     * Потолок выходных токенов: короткой фразе — короткий ответ, без бесконтрольной генерации.
     * 4 токена на слово: русское слово у Qwen — 2–4 токена плюс пунктуация; при 3 ответ на
     * фрагмент из 40 слов обрезался, и хвост реплики терялся.
     */
    private fun maxOutputTokensFor(chunk: String): Int {
        val words = chunk.trim().split(WHITESPACE).size
        return (words * 4 + 32).coerceIn(32, config.maxOutputTokens)
    }

    /**
     * Склейка фрагментов: фрагмент, продолжающий предложение (предыдущий не кончается на
     * `.`/`!`/`?`/`…`), начинается со строчной буквы — заглавную ему поставила модель
     * или согласователь только потому, что фрагмент начинал запрос.
     */
    private fun joinChunks(parts: List<String>): String {
        val result = StringBuilder()
        for (part in parts) {
            if (part.isEmpty()) continue
            val continues = result.isNotEmpty() && result.last() !in ".!?…"
            if (result.isNotEmpty()) result.append(' ')
            result.append(if (continues) part.replaceFirstChar { it.lowercaseChar() } else part)
        }
        return result.toString()
    }

    /** Деление текста на почти равные фрагменты по словам: без короткого хвоста из 1–2 слов без контекста. */
    private fun chunksOf(text: String): List<String> {
        val words = text.trim().split(WHITESPACE)
        if (words.size <= config.maxChunkWords) return listOf(text.trim())
        val count = (words.size + config.maxChunkWords - 1) / config.maxChunkWords
        val size = (words.size + count - 1) / count
        return words.chunked(size) { it.joinToString(" ") }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            if (sessionId != 0L) {
                // Сначала просим прервать активную генерацию, затем ждём освобождения
                // нативного mutex и освобождаем контекст и модель.
                bridge.cancel(sessionId)
                bridge.unload(sessionId)
                sessionId = 0
                loaded = false
            }
        }
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /** Крупная модель (Qwen2.5-1.5B): уровень «Умная». */
        const val SMART_MANIFEST = "llm-manifest.properties"

        /** Малая модель (Qwen2.5-0.5B): уровень «Быстрая». */
        const val FAST_MANIFEST = "llm-fast-manifest.properties"

        /**
         * Android-фабрика: читает llm-manifest, копирует GGUF с проверкой SHA-256 во внутреннее
         * хранилище (однократно, повторно не копируется) и отдаёт путь к файлу.
         */
        fun create(
            context: Context,
            config: LlamaConfig = LlamaConfig(),
            guard: CorrectionGuard = CorrectionGuard(),
            manifest: String = SMART_MANIFEST,
        ): LlamaTextCorrector {
            val appContext = context.applicationContext
            val assets = appContext.assets
            val store = BundledModelStore(File(appContext.noBackupFilesDir, "models")) { assets.open(it) }
            val openModel: (checkCancelled: () -> Unit) -> File = { checkCancelled ->
                val spec = assets.open(manifest).use { BundledModelSpec.readLlm(it) }
                val directory = store.prepare(spec, checkCancelled)
                File(directory, spec.files.single { it.name.endsWith(".gguf") }.name)
            }
            return LlamaTextCorrector(openModel, config, guard = guard)
        }
    }
}
