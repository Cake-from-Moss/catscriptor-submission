package org.cfm.catscriptor.native.models

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelDownloaderTest {
    private lateinit var server: HttpServer
    private lateinit var root: File
    private val hits = AtomicInteger()
    private val big = ByteArray(700_000) { (it * 31 + 7).toByte() }
    private val small = "licence text".toByteArray()

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Before fun setUp() {
        root = Files.createTempDirectory("models-test").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/big") { ex -> hits.incrementAndGet(); ex.sendResponseHeaders(200, big.size.toLong()); ex.responseBody.use { it.write(big) } }
            createContext("/small") { ex -> hits.incrementAndGet(); ex.sendResponseHeaders(200, small.size.toLong()); ex.responseBody.use { it.write(small) } }
            createContext("/moved") { ex -> ex.responseHeaders.add("Location", "/small"); ex.sendResponseHeaders(302, -1); ex.close() }
            createContext("/missing") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
            createContext("/short") { ex -> hits.incrementAndGet(); ex.sendResponseHeaders(200, 10); ex.responseBody.use { it.write(ByteArray(10)) } }
            start()
        }
    }

    @After fun tearDown() {
        server.stop(0)
        root.deleteRecursively()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    private fun spec(vararg files: ModelFileSpec) = BundledModelSpec("test-model", "models/test-model", files.toList())
    private fun bigFile(path: String = "/big", hash: String = sha(big)) = ModelFileSpec("weights.bin", big.size.toLong(), hash, url(path))
    private fun smallFile(path: String = "/small") = ModelFileSpec("LICENSE", small.size.toLong(), sha(small), url(path))
    private fun downloader(space: Long = Long.MAX_VALUE) = ModelDownloader(root, freeSpace = { space }, allowHttp = true)

    @Test fun downloadsVerifiesAndReportsProgress() = runBlocking {
        val models = listOf(spec(bigFile(), smallFile()))
        val progress = mutableListOf<DownloadProgress>()
        downloader().download(models) { progress += it }

        assertArrayEquals(big, File(root, "test-model/weights.bin").readBytes())
        assertArrayEquals(small, File(root, "test-model/LICENSE").readBytes())
        assertEquals(big.size + small.size.toLong(), progress.last().totalBytes)
        assertEquals(progress.last().totalBytes, progress.last().doneBytes)
        assertTrue("прогресс не убывает", progress.zipWithNext().all { (a, b) -> a.doneBytes <= b.doneBytes })
        assertTrue(File(root, "test-model").listFiles()!!.none { it.name.endsWith(".partial") })
        assertEquals(0L, downloader().missingBytes(models))
    }

    @Test fun doesNotRequestFilesThatAreAlreadyOnDisk() = runBlocking {
        val models = listOf(spec(bigFile(), smallFile()))
        downloader().download(models) {}
        hits.set(0)
        downloader().download(models) {}
        assertEquals(0, hits.get())
    }

    @Test fun wrongChecksumIsRejectedAndLeavesNothing() {
        val models = listOf(spec(bigFile(hash = "0".repeat(64))))
        val error = assertThrows(ModelDownloadException::class.java) { runBlocking { downloader().download(models) {} } }
        assertTrue(error.message!!, error.message!!.contains("SHA-256"))
        assertFalse(File(root, "test-model/weights.bin").exists())
        assertTrue(File(root, "test-model").listFiles()!!.isEmpty())
    }

    @Test fun truncatedBodyIsRejected() {
        val models = listOf(spec(ModelFileSpec("x.bin", 100, "0".repeat(64), url("/short"))))
        assertThrows(ModelDownloadException::class.java) { runBlocking { downloader().download(models) {} } }
        assertTrue(File(root, "test-model").listFiles()!!.isEmpty())
    }

    @Test fun followsRedirects() = runBlocking {
        downloader().download(listOf(spec(smallFile("/moved")))) {}
        assertArrayEquals(small, File(root, "test-model/LICENSE").readBytes())
    }

    @Test fun serverErrorIsReported() {
        val models = listOf(spec(smallFile("/missing")))
        val error = assertThrows(ModelDownloadException::class.java) { runBlocking { downloader().download(models) {} } }
        assertTrue(error.message!!, error.message!!.contains("404"))
    }

    @Test fun plainHttpIsRefusedByDefault() {
        val strict = ModelDownloader(root, freeSpace = { Long.MAX_VALUE })
        assertThrows(ModelDownloadException::class.java) { runBlocking { strict.download(listOf(spec(smallFile()))) {} } }
        assertEquals(0, hits.get())
    }

    @Test fun refusesToStartWithoutFreeSpace() {
        val models = listOf(spec(bigFile()))
        assertThrows(ModelDownloadException::class.java) { runBlocking { downloader(space = 1000).download(models) {} } }
        assertEquals(0, hits.get())
    }

    @Test fun cancellationLeavesNoPartialFile() {
        val models = listOf(spec(bigFile()))
        assertThrows(CancellationException::class.java) {
            runBlocking { downloader().download(models) { currentCoroutineContextCancel() } }
        }
        assertFalse(File(root, "test-model/weights.bin").exists())
        assertTrue(File(root, "test-model").listFiles()!!.isEmpty())
    }

    @Test fun downloadedModelIsPickedUpByTheStoreWithoutAssets() = runBlocking {
        val models = listOf(spec(bigFile(), smallFile()))
        downloader().download(models) {}
        val store = BundledModelStore(root) { throw IOException("assets must not be read") }
        val directory = store.prepare(models.single())
        assertArrayEquals(big, File(directory, "weights.bin").readBytes())
    }

    private fun currentCoroutineContextCancel() {
        // Колбэк прогресса не suspend: отменяем родительский runBlocking через бросок отмены.
        throw CancellationException("cancelled by test")
    }
}
