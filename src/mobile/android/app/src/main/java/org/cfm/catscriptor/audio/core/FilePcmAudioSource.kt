package org.cfm.catscriptor.audio.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import org.cfm.catscriptor.domain.pipeline.PcmAudioSource

/** Файл little-endian float32 mono 16 кГц; память чтения ограничена 16384 отсчётами. */
class FilePcmAudioSource(private val file: File, private val ownsFile: Boolean = true) : PcmAudioSource {
    private val handle = RandomAccessFile(file, "r")
    override val sampleCount: Long
    private var closed = false

    init {
        val length = try {
            handle.length()
        } catch (failure: Throwable) {
            try {
                handle.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            if (ownsFile) file.delete()
            throw failure
        }
        if (length % 4 != 0L) {
            handle.close()
            if (ownsFile) file.delete()
            throw IllegalArgumentException("Truncated float32 PCM")
        }
        sampleCount = length / 4
    }

    override suspend fun read(startSample: Long, maxSamples: Int): FloatArray = withContext(Dispatchers.IO) {
        require(startSample >= 0 && maxSamples > 0)
        coroutineContext.ensureActive()
        synchronized(this@FilePcmAudioSource) {
            check(!closed) { "PCM source is closed" }
            val count = minOf(maxSamples.toLong(), 16384L, (sampleCount - startSample).coerceAtLeast(0)).toInt()
            if (count == 0) return@synchronized FloatArray(0)
            handle.seek(startSample * 4)
            val bytes = ByteArray(count * 4)
            handle.readFully(bytes)
            coroutineContext.ensureActive()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            FloatArray(count) { buffer.float }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            handle.close()
        } finally {
            if (ownsFile && file.exists() && !file.delete()) {
                throw java.io.IOException("Cannot delete owned PCM file")
            }
        }
    }
}
