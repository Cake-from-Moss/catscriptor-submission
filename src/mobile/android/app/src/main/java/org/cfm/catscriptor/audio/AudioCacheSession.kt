package org.cfm.catscriptor.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.UUID

/** Межпроцессный lock остаётся у владельца PCM; уборка не затрагивает активные записи. */
internal class AudioCacheSession private constructor(
    val directory: File,
    private val handle: RandomAccessFile,
    private val lock: FileLock,
) : Closeable {
    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        cleanupAudio(null, { deleteOwned(directory) }, { lock.release() }, { handle.close() })
    }

    companion object {
        fun create(cache: File): AudioCacheSession {
            cleanStale(cache)
            val directory = File(cache, "audio-import-${UUID.randomUUID()}")
            if (!directory.mkdir()) throw java.io.IOException("Не удалось создать временный каталог аудио")
            var handle: RandomAccessFile? = null
            try {
                handle = RandomAccessFile(File(directory, ".lock"), "rw")
                return AudioCacheSession(directory, handle, handle.channel.lock())
            } catch (error: Exception) {
                cleanupAudio(error, { handle?.close() }, { deleteOwned(directory) })
                throw error
            }
        }

        private fun cleanStale(cache: File) {
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            val name = Regex("audio-import-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            cache.listFiles().orEmpty().forEach { directory ->
                if (!name.matches(directory.name) || !directory.isDirectory || directory.lastModified() >= cutoff) return@forEach
                val marker = File(directory, ".lock")
                if (!marker.isFile) return@forEach
                try {
                    RandomAccessFile(marker, "rw").use { handle ->
                        val lock = try { handle.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                        lock?.use {
                            // Только известные файлы собственного формата, без рекурсивного удаления.
                            deleteOwned(directory)
                        }
                    }
                } catch (_: java.io.IOException) {
                    // Уборка best-effort: чужой сбой диска не мешает открыть новый документ.
                }
            }
        }

        private fun deleteOwned(directory: File) {
            // При неудаче оставляем marker для следующей безопасной уборки после освобождения lock.
            cleanupAudio(null,
                { deleteChecked(File(directory, "input")) },
                { deleteChecked(File(directory, "output.f32")) },
            )
            deleteChecked(File(directory, ".lock"))
            deleteChecked(directory)
        }

        private fun deleteChecked(file: File) {
            if (file.exists() && !file.delete()) throw AudioImportException(
                AudioImportException.Reason.STORAGE, "Не удалось удалить временные аудиоданные",
            )
        }
    }
}
