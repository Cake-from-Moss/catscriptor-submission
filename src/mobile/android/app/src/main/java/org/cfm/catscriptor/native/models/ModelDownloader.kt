package org.cfm.catscriptor.native.models

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Сколько байт скачано из скольки; [fileName] — файл, который сейчас загружается. */
data class DownloadProgress(val doneBytes: Long, val totalBytes: Long, val fileName: String)

/** Ошибка загрузки модели; текст показывается пользователю. */
class ModelDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Скачивание моделей для сборки `lite` в ту же папку, откуда читает [BundledModelStore]
 * (`noBackupFilesDir/models/<id>/<файл>`). После скачивания движки находят файлы так же, как
 * скопированные из APK: [BundledModelStore.prepare] видит совпадающий SHA-256 и ничего не копирует.
 *
 * Каждый файл пишется во временный `.partial`, на лету считается SHA-256, и только после проверки
 * размера и хеша файл атомарно становится на место. Незавершённый или повреждённый файл не
 * подменяет рабочий. Уже скачанные файлы не загружаются повторно, поэтому после обрыва сети
 * достаточно повторить загрузку.
 *
 * Только HTTPS (редиректы разбираются вручную, чтобы нельзя было уйти на http). Данные
 * пользователя в сеть не отправляются: запрашиваются только файлы моделей.
 *
 * @param freeSpace доступное место в байтах; подменяется в тестах.
 * @param allowHttp только для тестов с локальным сервером.
 */
class ModelDownloader(
    private val root: File,
    private val freeSpace: () -> Long = { root.apply { mkdirs() }.usableSpace },
    private val allowHttp: Boolean = false,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    /** Файлы, которых нет на диске в нужном размере. Проверка дешёвая (без хеша). */
    fun missing(specs: List<BundledModelSpec>): List<Pair<BundledModelSpec, ModelFileSpec>> =
        specs.flatMap { spec ->
            spec.files.filterNot { isPresent(spec, it) }.map { spec to it }
        }

    fun missingBytes(specs: List<BundledModelSpec>): Long = missing(specs).sumOf { it.second.size }

    suspend fun download(specs: List<BundledModelSpec>, onProgress: (DownloadProgress) -> Unit) =
        withContext(Dispatchers.IO) {
            val pending = missing(specs)
            val total = pending.sumOf { it.second.size }
            if (total == 0L) return@withContext
            // Запас на временный файл: модель весит до 1,1 ГБ, а скачивается рядом с готовыми.
            val largest = pending.maxOf { it.second.size }
            if (freeSpace() < total + largest / 4) {
                throw ModelDownloadException("Недостаточно места: нужно ещё около ${total / MIB + 1} МиБ")
            }
            var done = 0L
            for ((spec, file) in pending) {
                currentCoroutineContext().ensureActive()
                val directory = File(root, spec.id)
                if (!directory.isDirectory && !directory.mkdirs()) {
                    throw ModelDownloadException("Не удалось создать папку модели ${spec.id}")
                }
                fetch(file, File(directory, file.name)) { bytes ->
                    done += bytes
                    onProgress(DownloadProgress(done, total, file.name))
                }
            }
        }

    private fun isPresent(spec: BundledModelSpec, file: ModelFileSpec): Boolean =
        File(File(root, spec.id), file.name).let { it.isFile && it.length() == file.size }

    private suspend fun fetch(file: ModelFileSpec, target: File, onBytes: (Long) -> Unit) {
        val url = file.url ?: throw ModelDownloadException("Для ${file.name} не указан адрес загрузки")
        val temporary = File.createTempFile("model-", ".partial", target.parentFile)
        try {
            val connection = open(url)
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                connection.inputStream.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > file.size) {
                                throw ModelDownloadException("Файл ${file.name} больше ожидаемого")
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            onBytes(count.toLong())
                        }
                        output.fd.sync()
                    }
                }
                if (total != file.size) {
                    throw ModelDownloadException("Файл ${file.name} скачан не полностью ($total из ${file.size} байт)")
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (hash != file.sha256) {
                    throw ModelDownloadException("Файл ${file.name} повреждён: не совпал SHA-256")
                }
            } finally {
                connection.disconnect()
            }
            currentCoroutineContext().ensureActive()
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: ModelDownloadException) {
            throw error
        } catch (error: IOException) {
            throw ModelDownloadException("Не удалось скачать ${file.name}: ${error.message ?: "ошибка сети"}", error)
        } finally {
            temporary.delete()
        }
    }

    private fun open(start: String): HttpURLConnection {
        var current = start
        repeat(MAX_REDIRECTS + 1) {
            val url = URL(current)
            if (url.protocol != "https" && !(allowHttp && url.protocol == "http")) {
                throw ModelDownloadException("Разрешена только загрузка по HTTPS")
            }
            val connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("User-Agent", "CatScriptor-model-downloader")
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> return connection
                301, 302, 303, 307, 308 -> {
                    val next = connection.getHeaderField("Location")
                    connection.disconnect()
                    current = URL(url, next ?: throw ModelDownloadException("Редирект без адреса")).toString()
                }
                else -> {
                    connection.disconnect()
                    throw ModelDownloadException("Сервер ответил $code на запрос $current")
                }
            }
        }
        throw ModelDownloadException("Слишком много перенаправлений")
    }

    private companion object {
        const val MIB = 1024L * 1024
        const val BUFFER = 256 * 1024
        const val MAX_REDIRECTS = 5
    }
}
