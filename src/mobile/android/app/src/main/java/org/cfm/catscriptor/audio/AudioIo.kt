package org.cfm.catscriptor.audio

import java.io.IOException

/** Добавляет ошибки освобождения к исходной, не подменяя отмену корутины. */
internal fun cleanupAudio(original: Throwable?, vararg actions: () -> Unit) {
    var failure = original
    actions.forEach { action ->
        try { action() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
        }
    }
    if (original == null) failure?.let { throw it }
}

internal inline fun <T> audioIo(reason: AudioImportException.Reason, operation: String, block: () -> T): T =
    try { block() } catch (error: AudioImportException) { throw error }
    catch (error: IOException) { throw AudioImportException(reason, operation, error) }
