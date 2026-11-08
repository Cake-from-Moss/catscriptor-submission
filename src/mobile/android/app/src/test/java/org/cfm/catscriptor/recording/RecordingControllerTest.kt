package org.cfm.catscriptor.recording

import java.io.File
import kotlinx.coroutines.test.*
import org.cfm.catscriptor.domain.app.*
import org.junit.Assert.*
import org.junit.Test

class RecordingControllerTest {
    @Test fun liveNotesUsePauseClockAndSurviveStopHandoff() = runTest {
        val file = File.createTempFile("notes-recording", ".wav")
        var now = 0L
        var saved: List<RecordingNote> = emptyList()
        var savedId: String? = null
        val controller = RecordingController({ true }, { Device() }, storage(file), { now },
            { _, _ -> ImportResult.Accepted("recording") }, backgroundScope,
            saveNotes = { id, notes -> savedId = id; saved = notes })
        try {
            controller.start(true)
            now = 400
            controller.addNote("  Важная мысль  ")
            controller.pause()
            now = 900
            controller.addMark()
            val notes = (controller.state.value as RecorderState.Active).notes
            assertEquals(listOf(400L, 400L), notes.map { it.positionMs })
            assertEquals("Важная мысль", notes.first().text)
            controller.addNote("К началу набора", positionMs = 200)
            assertEquals(200L, (controller.state.value as RecorderState.Active).notes.first().positionMs)
            controller.deleteNote(notes.last().id)
            assertEquals("recording", controller.stop())
            assertEquals("recording", savedId)
            assertEquals(listOf(200L, 400L), saved.map { it.positionMs })
            assertEquals(notes.first(), saved.last())
            assertEquals(RecorderState.Idle, controller.state.value)
        } finally { file.delete() }
    }

    @Test fun cancelDiscardsLiveNotesAndNextSessionStartsEmpty() = runTest {
        val file = File.createTempFile("notes-cancel", ".wav")
        var saves = 0
        val controller = RecordingController({ true }, { Device() }, storage(file), { 100 },
            { _, _ -> error("Отмена без импорта") }, backgroundScope,
            saveNotes = { _, _ -> saves++ })
        try {
            controller.start(true)
            controller.addNote("Не сохранять")
            controller.cancel()
            controller.start(false)
            assertTrue((controller.state.value as RecorderState.Active).notes.isEmpty())
            controller.cancel()
            assertEquals(0, saves)
        } finally { file.delete() }
    }

    @Test fun cancelPausedRecordingDeletesWithoutImportAndIsIdempotent() = runTest {
        val file = File.createTempFile("cancel-recording", ".wav")
        val other = File.createTempFile("keep-recording", ".wav").apply { writeText("keep") }
        val device = Device()
        val controller = RecordingController({ true }, { device }, storage(file), { 0 },
            { _, _ -> error("Отменённая запись не импортируется") }, backgroundScope)
        try {
            controller.start(true)
            controller.pause()
            controller.cancel()
            controller.cancel()
            assertNull(controller.stop())
            assertFalse(file.exists())
            assertEquals("keep", other.readText())
            assertEquals(1, device.releases)
            assertEquals(RecorderState.Idle, controller.state.value)
        } finally { file.delete(); other.delete() }
    }

    private class Device : RecordingDevice {
        var released = false
        var failStop = false
        var failStart = false
        var failPause = false
        var failResume = false
        var releases = 0
        var starts = 0
        var pauses = 0
        var resumes = 0
        var onStart: () -> Unit = {}
        var error: () -> Unit = {}
        override fun start(file: File, onError: () -> Unit) {
            starts++; file.writeText("container"); error = onError; onStart(); check(!failStart)
        }
        override fun pause() { pauses++; check(!failPause) }
        override fun resume() { resumes++; check(!failResume) }
        override fun amplitude() = 32767
        override fun stop() { check(!failStop) }
        override fun release() { released = true; releases++ }
    }

    @Test fun pauseClockMarksAndSingleHandoff() = runTest {
        val file = File.createTempFile("recording-test", ".m4a")
        val device = Device()
        var now = 0L
        var imports = 0
        val storage = storage(file)
        val controller = RecordingController({ true }, { device }, storage, { now }, { uri, _ ->
            assertEquals("content://recordings/test", uri); imports++; ImportResult.Accepted("id")
        }, backgroundScope)
        try {
            assertEquals(StartRecordingResult.Started, controller.start(true))
            assertEquals(StartRecordingResult.Unavailable, controller.start(false))
            now = 400; controller.pause()
            now = 900; controller.addMark()
            assertEquals(listOf(400L), (controller.state.value as RecorderState.Active).marksMs)
            controller.resume(); now = 1200; controller.pause()
            val state = controller.state.value as RecorderState.Active
            assertEquals(700L, state.elapsedMs)
            assertTrue(state.liveLines.isEmpty())
            assertEquals("id", controller.stop())
            assertNull(controller.stop())
            assertEquals(1, imports)
            assertTrue(device.released)
            assertTrue(file.exists())
        } finally { file.delete() }
    }

    @Test fun shortStopAndAsyncErrorDeleteOnlyOwnPartial() = runTest {
        for (asyncError in listOf(false, true)) {
            val file = File.createTempFile("recording-test", ".m4a")
            val device = Device().apply { failStop = true }
            val controller = RecordingController({ true }, { device }, storage(file), { 0 },
                { _, _ -> error("Нельзя импортировать повреждённый файл") }, backgroundScope)
            controller.start(false)
            if (asyncError) device.error()
            assertNull(controller.stop())
            assertTrue(device.released)
            assertFalse(file.exists())
        }
    }

    @Test fun queueErrorPreservesFinishedFile() = runTest {
        val file = File.createTempFile("recording-test", ".m4a")
        val controller = RecordingController({ true }, { Device() }, storage(file), { 0 },
            { _, _ -> error("Очередь недоступна") }, backgroundScope)
        try {
            controller.start(false)
            assertNull(controller.stop())
            assertTrue(file.exists())
        } finally { file.delete() }
    }

    @Test fun lowStorageDoesNotCreateSession() = runTest {
        val controller = RecordingController({ true }, { error("Не создавать рекордер") },
            object : RecordingStorage {
                override fun availableBytes() = 0L
                override fun create(): File = error("Не создавать файл")
                override fun uri(file: File) = error("Нет файла")
            }, { 0 }, { _, _ -> ImportResult.UnsupportedFormat }, backgroundScope)
        assertEquals(StartRecordingResult.LowStorage(0), controller.start(false))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun pollingShowsPeakAndFinalizesWhenStorageRunsOut() = runTest {
        val file = File.createTempFile("recording-test", ".m4a")
        val device = Device()
        var bytes = Long.MAX_VALUE
        val storage = object : RecordingStorage {
            override fun availableBytes() = bytes
            override fun create() = file
            override fun uri(file: File) = "content://recordings/test"
        }
        val controller = RecordingController({ true }, { device }, storage, { testScheduler.currentTime },
            { _, _ -> error("Не запускать очередь скрытно") }, backgroundScope)
        try {
            controller.start(false)
            runCurrent(); advanceTimeBy(101); runCurrent()
            assertEquals(0f, (controller.state.value as RecorderState.Active).levelDbfs)
            bytes = 0
            advanceTimeBy(100); runCurrent()
            assertEquals(RecorderState.Idle, controller.state.value)
            assertTrue(device.released)
            assertTrue(file.exists())
        } finally { file.delete() }
    }

    @Test fun cancelledHandoffKeepsFinishedFile() = runTest {
        val file = File.createTempFile("recording-test", ".m4a")
        val device = Device()
        val controller = RecordingController({ true }, { device }, storage(file), { 0 },
            { _, _ -> throw kotlinx.coroutines.CancellationException() }, backgroundScope)
        try {
            controller.start(false)
            try { controller.stop(); fail("Ожидалась отмена") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertTrue(device.released)
            assertTrue(file.exists())
            assertEquals(RecorderState.Idle, controller.state.value)
        } finally { file.delete() }
    }

    private fun storage(file: File) = object : RecordingStorage {
        override fun availableBytes() = Long.MAX_VALUE
        override fun create() = file
        override fun uri(file: File) = "content://recordings/test"
    }

    @Test fun startFailuresAreIsolatedAndReleaseExactlyOnce() = runTest {
        val old = File.createTempFile("older-recording", ".m4a").apply { writeText("keep") }
        try {
            for (failure in listOf("availability", "factory", "start", "callback")) {
                val file = File.createTempFile("recording-test", ".m4a")
                val device = Device().apply {
                    failStart = failure == "start"
                    if (failure == "callback") onStart = { error() }
                }
                val controller = RecordingController(
                    { check(failure != "availability"); true },
                    { check(failure != "factory"); device }, storage(file), { 0 },
                    { _, _ -> error("Не импортировать") }, backgroundScope)
                assertEquals(StartRecordingResult.Unavailable, controller.start(false))
                assertEquals(RecorderState.Idle, controller.state.value)
                assertEquals(if (failure in listOf("start", "callback")) 1 else 0, device.releases)
                if (failure != "availability") assertFalse(file.exists())
                assertEquals("keep", old.readText())
                file.delete()
            }
        } finally { old.delete() }
    }

    @Test fun pauseResumeFailureReleasesAndStaleCallbackCannotDiscardNextSession() = runTest {
        for (failPause in listOf(true, false)) {
            val first = File.createTempFile("recording-test", ".m4a")
            val second = File.createTempFile("recording-test", ".m4a")
            val devices = listOf(Device().apply { this.failPause = failPause; failResume = !failPause }, Device())
            var index = 0
            val storage = object : RecordingStorage {
                override fun availableBytes() = Long.MAX_VALUE
                override fun create() = if (index == 0) first else second
                override fun uri(file: File) = "content://recordings/test"
            }
            val controller = RecordingController({ true }, { devices[index++] }, storage, { 0 },
                { _, _ -> ImportResult.Accepted("id") }, backgroundScope)
            try {
                controller.start(false)
                controller.pause()
                if (!failPause) controller.resume()
                assertEquals(RecorderState.Idle, controller.state.value)
                assertEquals(1, devices[0].releases)
                assertFalse(first.exists())
                controller.start(false)
                devices[0].error()
                assertTrue(controller.state.value is RecorderState.Active)
                assertEquals("id", controller.stop())
                assertTrue(second.exists())
            } finally { first.delete(); second.delete() }
        }
    }

    @Test fun setupTimeExcludedAndDuplicatePauseResumeAreIdempotent() = runTest {
        val file = File.createTempFile("recording-test", ".m4a")
        var clock = 0L
        val device = Device().apply { onStart = { clock = 5000 } }
        val controller = RecordingController({ true }, { device }, storage(file), { clock },
            { _, _ -> ImportResult.Accepted("id") }, backgroundScope)
        try {
            controller.pause(); controller.resume()
            controller.start(false)
            clock = 5100
            controller.pause(); controller.pause()
            assertEquals(100L, (controller.state.value as RecorderState.Active).elapsedMs)
            controller.resume(); controller.resume()
            assertEquals(1, device.pauses)
            assertEquals(1, device.resumes)
            controller.stop(); controller.stop()
            assertEquals(1, device.releases)
        } finally { file.delete() }
    }

    @Test fun cancellationDuringStartReleasesPartialExactlyOnce() = runTest {
        val file = File.createTempFile("recording-test", ".m4a")
        val device = Device().apply { onStart = { throw kotlinx.coroutines.CancellationException() } }
        val controller = RecordingController({ true }, { device }, storage(file), { 0 },
            { _, _ -> error("Не импортировать") }, backgroundScope)
        try { controller.start(false); fail("Ожидалась отмена") }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(1, device.releases)
        assertFalse(file.exists())
        assertEquals(RecorderState.Idle, controller.state.value)
    }
}
