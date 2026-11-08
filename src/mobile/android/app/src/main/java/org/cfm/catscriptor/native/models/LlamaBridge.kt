package org.cfm.catscriptor.native.models

/**
 * Нативный inference backend локальной LLM. Контракт узкий, чтобы JVM-тесты могли
 * подставлять фейк без Android и JNI: загрузка по пути к GGUF, покадровая генерация,
 * отмена флагом и идемпотентное освобождение.
 */
internal interface LlamaBridge {
    /** Создать сессию; вернуть идентификатор для остальных вызовов. */
    fun createSession(): Long

    /** Загрузить модель и создать контекст. Повторный вызов для загруженной сессии — no-op. */
    fun loadModel(sessionId: Long, modelPath: String, contextSize: Int, threads: Int)

    /**
     * Сгенерировать ответ ассистента на сообщениях system+user (chat template применяется
     * нативно). Останавливается по EOG, [maxTokens] или отмене; отмена бросает
     * [java.util.concurrent.CancellationException].
     */
    fun generate(
        sessionId: Long,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        presencePenalty: Float,
    ): String

    /** Попросить прервать текущую загрузку/генерацию на ближайшей безопасной точке. */
    fun cancel(sessionId: Long)

    /** Освободить контекст и модель; не раньше завершения активного вызова. Повторно — безопасно. */
    fun unload(sessionId: Long)
}

/** Параметры загрузки и генерации LLM по умолчанию; см. docs/llm-correction.md. */
data class LlamaConfig(
    val contextSize: Int = 1024,
    val threads: Int = defaultLlamaThreads(Runtime.getRuntime().availableProcessors()),
    /** 0f — жадное декодирование (детерминированно); >0 — top_k/top_p/temp. */
    val temperature: Float = 0f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    /**
     * Штраф за повтор уже сгенерированных токенов. Для коррекции он вреден: ответ обязан
     * повторять слова, запятые и частицы исходника, а штраф толкает модель их заменять или
     * пропускать. Зацикливание ограничено [maxOutputTokens] и проверкой [CorrectionGuard].
     */
    val presencePenalty: Float = 0f,
    /** Потолок выходных токенов на один фрагмент реплики. */
    val maxOutputTokens: Int = 192,
    /**
     * Длинная реплика правится частями не длиннее стольких слов: русское слово — 2–4 токена
     * Qwen, и ответ на 150 слов не помещается ни в [maxOutputTokens], ни в контекст 1024.
     * Делится только текст для LLM; таймкоды и границы реплик не меняются.
     */
    val maxChunkWords: Int = 40,
)

/** Верхняя граница потоков llama.cpp: дальше прирост упирается в пропускную способность памяти. */
internal const val MAX_LLAMA_THREADS = 4

/**
 * Число потоков llama.cpp для устройства с [availableProcessors] ядрами.
 * Потоков не больше, чем ядер: лишние потоки ggml ждут друг друга активным ожиданием
 * и на 1–2 ядрах (эмулятор, слабый телефон) генерация замедляется в разы.
 */
internal fun defaultLlamaThreads(availableProcessors: Int): Int =
    availableProcessors.coerceIn(1, MAX_LLAMA_THREADS)
