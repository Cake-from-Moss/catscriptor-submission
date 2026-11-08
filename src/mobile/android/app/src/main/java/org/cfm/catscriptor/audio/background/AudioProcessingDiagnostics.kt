package org.cfm.catscriptor.audio.background

import android.util.Log

/** Сообщения исключений и cause.toString могут содержать URI или данные документа. */
internal fun logProcessingError(operation: String, error: Exception) {
    Log.e("AudioProcessing", "$operation: ${error.javaClass.name}\n" +
        error.stackTrace.joinToString("\n") { "    at $it" })
}
