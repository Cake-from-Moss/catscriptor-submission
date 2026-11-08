package org.cfm.catscriptor.native.models

/**
 * JNI-обёртка над llama.cpp (llm-jni.cpp, библиотека `llm-jni`). Статусная загрузка библиотеки
 * при первом обращении; нативные ошибки приходят как Java-исключения из JNI-методов.
 */
internal object LlamaNativeBridge : LlamaBridge {
    init {
        System.loadLibrary("llm-jni")
    }

    override fun createSession(): Long = nativeCreateSession()

    override fun loadModel(sessionId: Long, modelPath: String, contextSize: Int, threads: Int) {
        nativeLoadModel(sessionId, modelPath, contextSize, threads)
    }

    override fun generate(
        sessionId: Long,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        presencePenalty: Float,
    ): String = nativeGenerate(
        sessionId, systemPrompt, userPrompt, maxTokens, temperature, topP, topK, presencePenalty,
    )

    override fun cancel(sessionId: Long) = nativeCancel(sessionId)

    override fun unload(sessionId: Long) = nativeUnload(sessionId)

    private external fun nativeCreateSession(): Long
    private external fun nativeLoadModel(sessionId: Long, modelPath: String, contextSize: Int, threads: Int)
    private external fun nativeGenerate(
        sessionId: Long,
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        presencePenalty: Float,
    ): String
    private external fun nativeCancel(sessionId: Long)
    private external fun nativeUnload(sessionId: Long)
}
