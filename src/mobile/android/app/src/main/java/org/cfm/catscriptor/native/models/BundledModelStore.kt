package org.cfm.catscriptor.native.models

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/** Ошибка подготовки упакованной модели до обращения к нативному движку. */
class ModelPreparationException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

data class ModelFileSpec(val name: String, val size: Long, val sha256: String)

/** Манифест проверенного комплекта; набор обязательных файлов задаётся типом модели. */
data class BundledModelSpec(
    val id: String,
    val assetDirectory: String,
    val files: List<ModelFileSpec>,
) {
    companion object {
        fun read(input: InputStream): BundledModelSpec {
            return readFiles(input, setOf("model.int8.onnx", "tokens.txt", "LICENSE"))
        }

        fun readVad(input: InputStream): BundledModelSpec {
            return readFiles(input, setOf("silero_vad.onnx", "LICENSE"))
        }

        /**
         * Комплект локальной LLM: ровно один .gguf и LICENSE. Имя весов произвольное,
         * поэтому вместо фиксированного списка — структурная проверка, строгость та же.
         */
        fun readLlm(input: InputStream): BundledModelSpec {
            return readFiles(input, null).also { spec ->
                val names = spec.files.map { it.name }
                require(names.count { it.endsWith(".gguf") } == 1) {
                    "В комплекте LLM должен быть ровно один файл .gguf"
                }
                require("LICENSE" in names) { "В комплекте LLM отсутствует LICENSE" }
            }
        }

        /** Комплект диаризации: только эмбеддинги CAM++ и их лицензия (кластеризация — в Kotlin). */
        fun readDiarization(input: InputStream): BundledModelSpec {
            return readFiles(input, setOf("embedding.onnx", "LICENSE-3dspeaker"))
        }

        private fun readFiles(input: InputStream, requiredFiles: Set<String>?): BundledModelSpec {
            val properties = Properties().apply { load(input) }
            fun value(key: String) = requireNotNull(properties.getProperty(key)) {
                "В манифесте модели отсутствует $key"
            }
            fun safePart(part: String): String {
                require(part.matches(Regex("[A-Za-z0-9_.-]+")) && part != "." && part != "..") {
                    "Недопустимый путь модели: $part"
                }
                return part
            }
            return BundledModelSpec(
                id = safePart(value("model.id")),
                assetDirectory = value("model.directory").also { directory ->
                    directory.split('/').forEach { safePart(it) }
                },
                files = value("files").split(',').map { name ->
                    ModelFileSpec(
                        safePart(name),
                        value("$name.size").toLong().also { require(it > 0) },
                        value("$name.sha256").also { require(it.matches(Regex("[0-9a-f]{64}"))) },
                    )
                }.also { files ->
                    if (requiredFiles != null) {
                        require(files.map { it.name }.toSet() == requiredFiles) {
                            "Несовместимый комплект модели"
                        }
                        require(files.size == requiredFiles.size)
                    }
                },
            )
        }
    }
}

/**
 * Потоковая проверка и атомарное копирование во внутреннюю папку без резервного копирования.
 * Вызывать вне UI-потока. Блокировка файла защищает также от второго экземпляра хранилища.
 */
class BundledModelStore(
    private val root: File,
    private val openAsset: (String) -> InputStream,
) {
    fun prepare(spec: BundledModelSpec, checkCancelled: () -> Unit = {}): File = synchronized(lock) {
        try {
            val directory = File(root, spec.id)
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw ModelPreparationException("Не удалось создать папку модели: ${spec.id}")
            }
            java.io.RandomAccessFile(File(root, ".prepare.lock"), "rw").use { file ->
                file.channel.use { channel ->
                    channel.lock().use {
                        spec.files.forEach { expected ->
                            checkCancelled()
                            val target = File(directory, expected.name)
                            if (!matches(target, expected, checkCancelled)) {
                                copyVerified(spec, expected, target, checkCancelled)
                            }
                        }
                    }
                }
            }
            directory
        } catch (error: IOException) {
            throw ModelPreparationException("Не удалось подготовить модель ${spec.id}: ${error.message}", error)
        }
    }

    private fun matches(file: File, spec: ModelFileSpec, checkCancelled: () -> Unit): Boolean {
        if (!file.isFile || file.length() != spec.size) return false
        return file.inputStream().buffered().use { hash(it, checkCancelled) } == spec.sha256
    }

    private fun copyVerified(
        model: BundledModelSpec,
        expected: ModelFileSpec,
        target: File,
        checkCancelled: () -> Unit,
    ) {
        val temporary = File.createTempFile("model-", ".partial", target.parentFile)
        try {
            openAsset("${model.assetDirectory}/${expected.name}").use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var total = 0L
                    while (true) {
                        checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > expected.size) {
                            throw ModelPreparationException("Повреждён файл ${expected.name}: превышен размер")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (!matches(temporary, expected, checkCancelled)) {
                throw ModelPreparationException("Повреждён файл ${expected.name}: неверный размер или SHA-256")
            }
            checkCancelled()
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    private fun hash(input: InputStream, checkCancelled: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val lock = Any()
    }
}
