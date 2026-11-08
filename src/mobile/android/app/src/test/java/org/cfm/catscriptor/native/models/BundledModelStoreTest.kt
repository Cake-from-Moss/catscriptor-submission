package org.cfm.catscriptor.native.models

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class BundledModelStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "проверяем модель".toByteArray()
    private val expected = ModelFileSpec("model.int8.onnx", bytes.size.toLong(), sha(bytes))
    private val spec = BundledModelSpec("test", "models/test", listOf(expected))

    @Test fun copiesOnceAndRepairsCorruptionWithoutChangingValidFiles() {
        var opens = 0
        val store = BundledModelStore(temporary.root) { opens++; ByteArrayInputStream(bytes) }
        val directory = store.prepare(spec)
        val file = File(directory, expected.name)
        assertArrayEquals(bytes, file.readBytes())
        val modified = file.lastModified()
        store.prepare(spec)
        assertEquals(1, opens)
        assertEquals(modified, file.lastModified())
        file.writeBytes(ByteArray(bytes.size))
        store.prepare(spec)
        assertEquals(2, opens)
        assertArrayEquals(bytes, file.readBytes())
        assertFalse(directory.listFiles()!!.any { it.name.endsWith(".partial") })
    }

    @Test fun missingAssetHasActionableError() {
        val store = BundledModelStore(temporary.root) { throw FileNotFoundException(it) }
        val error = assertThrows(ModelPreparationException::class.java) { store.prepare(spec) }
        assertTrue(error.message!!.contains("model.int8.onnx"))
    }

    @Test fun rejectsTruncatedOversizedAndWrongHashAssets() {
        for (data in listOf(bytes.copyOf(2), bytes + bytes, ByteArray(bytes.size))) {
            val root = temporary.newFolder()
            val store = BundledModelStore(root) { ByteArrayInputStream(data) }
            assertThrows(ModelPreparationException::class.java) { store.prepare(spec) }
            val directory = File(root, spec.id)
            assertFalse(File(directory, expected.name).exists())
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun cancellationLeavesNoPartialFileAndKeepsPreviousTarget() {
        val directory = File(temporary.root, spec.id).apply { mkdirs() }
        val target = File(directory, expected.name).apply { writeText("old") }
        val store = BundledModelStore(temporary.root) { ByteArrayInputStream(bytes) }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            store.prepare(spec) { if (++checks == 3) throw CancellationException() }
        }
        assertEquals("old", target.readText())
        assertEquals(listOf(expected.name), directory.list()!!.toList())
    }

    @Test fun concurrentPreparationCopiesOnce() {
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val store = BundledModelStore(temporary.root) { opens.incrementAndGet(); ByteArrayInputStream(bytes) }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { pool.submit<File> { store.prepare(spec) } }
            futures.forEach { it.get() }
            assertEquals(1, opens.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun rejectsIncompatibleAndUnsafeManifests() {
        val valid = """
            model.id=test
            model.directory=models/test
            files=model.int8.onnx,tokens.txt,LICENSE
            model.int8.onnx.size=1
            model.int8.onnx.sha256=${sha(byteArrayOf(1))}
            tokens.txt.size=1
            tokens.txt.sha256=${sha(byteArrayOf(1))}
            LICENSE.size=1
            LICENSE.sha256=${sha(byteArrayOf(1))}
        """.trimIndent()
        assertEquals(3, BundledModelSpec.read(valid.byteInputStream()).files.size)
        for (invalid in listOf(
            valid.replace("model.id=test", "model.id=.."),
            valid.replace("models/test", "../test"),
            valid.replace("files=model.int8.onnx,tokens.txt,LICENSE", "files=unknown.onnx"),
            valid.replace("tokens.txt.size=1", "tokens.txt.size=-1"),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                BundledModelSpec.read(invalid.byteInputStream())
            }
        }
    }

    @Test fun vadHasItsOwnStrictManifestAndDoesNotReplaceGigaam() {
        val manifest = """
            model.id=silero-test
            model.directory=models/silero-test
            files=silero_vad.onnx,LICENSE
            silero_vad.onnx.size=1
            silero_vad.onnx.sha256=${sha(byteArrayOf(1))}
            LICENSE.size=1
            LICENSE.sha256=${sha(byteArrayOf(1))}
        """.trimIndent()
        assertEquals(setOf("silero_vad.onnx", "LICENSE"),
            BundledModelSpec.readVad(manifest.byteInputStream()).files.map { it.name }.toSet())
        assertThrows(IllegalArgumentException::class.java) { BundledModelSpec.read(manifest.byteInputStream()) }
        assertThrows(IllegalArgumentException::class.java) {
            BundledModelSpec.readVad(manifest.replace("silero_vad.onnx,LICENSE", "silero_vad.onnx,LICENSE,LICENSE").byteInputStream())
        }
    }

    @Test fun llmManifestRequiresSingleGgufAndLicenseAndStaysIndependent() {
        val manifest = """
            model.id=qwen-test
            model.directory=models/qwen-test
            files=qwen-test.gguf,LICENSE
            qwen-test.gguf.size=1
            qwen-test.gguf.sha256=${sha(byteArrayOf(1))}
            LICENSE.size=1
            LICENSE.sha256=${sha(byteArrayOf(1))}
        """.trimIndent()
        assertEquals(setOf("qwen-test.gguf", "LICENSE"),
            BundledModelSpec.readLlm(manifest.byteInputStream()).files.map { it.name }.toSet())
        // ASR/VAD-парсеры не принимают LLM-комплект и наоборот.
        assertThrows(IllegalArgumentException::class.java) { BundledModelSpec.read(manifest.byteInputStream()) }
        assertThrows(IllegalArgumentException::class.java) { BundledModelSpec.readVad(manifest.byteInputStream()) }
        // Нет LICENSE или больше одного .gguf — отказ.
        assertThrows(IllegalArgumentException::class.java) {
            BundledModelSpec.readLlm(manifest.replace(",LICENSE", "").byteInputStream())
        }
        val twoGguf = """
            model.id=qwen-test
            model.directory=models/qwen-test
            files=a.gguf,b.gguf,LICENSE
            a.gguf.size=1
            a.gguf.sha256=${sha(byteArrayOf(1))}
            b.gguf.size=1
            b.gguf.sha256=${sha(byteArrayOf(2))}
            LICENSE.size=1
            LICENSE.sha256=${sha(byteArrayOf(1))}
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) {
            BundledModelSpec.readLlm(twoGguf.byteInputStream())
        }
    }

    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { "%02x".format(it) }
}
