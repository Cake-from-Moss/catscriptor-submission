package org.cfm.catscriptor.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaPlaybackControllerTest {
    private class FakeEngine : AudioEngine {
        var uri: String? = null
        var prepared: ((Long) -> Unit)? = null
        var error: (() -> Unit)? = null
        var completion: (() -> Unit)? = null
        var playing = false
        var currentSpeed = 1f
        var position = 0L
        var released = false
        val speedCalls = mutableListOf<Float>()

        override fun open(uri: String, onPrepared: (Long) -> Unit, onError: () -> Unit, onCompletion: () -> Unit) {
            this.uri = uri; prepared = onPrepared; error = onError; completion = onCompletion
        }
        override fun start(speed: Float) { playing = true; currentSpeed = speed }
        override fun pause() { playing = false }
        override fun seekTo(positionMs: Long) { position = positionMs }
        override fun setSpeed(speed: Float) { speedCalls += speed; currentSpeed = speed }
        override fun positionMs() = position
        override fun release() { released = true }
    }

    private val engines = mutableListOf<FakeEngine>()

    private fun TestScope.controller(uris: Map<String, String> = mapOf("rec" to "content://rec")) =
        MediaPlaybackController(
            audioUri = { uris[it] },
            engineFactory = { FakeEngine().also { engines += it } },
            scope = backgroundScope,
        )

    @Test
    fun loadOpensRecordingFileAndBecomesAvailableAfterPrepare() = runTest {
        val player = controller()
        player.load("rec")
        assertEquals("content://rec", engines.single().uri)
        assertFalse(player.state.value.isAvailable)

        engines.single().prepared!!(42_000)
        assertTrue(player.state.value.isAvailable)
        assertEquals(42_000, player.state.value.durationMs)
        assertEquals("rec", player.state.value.recordingId)
    }

    @Test
    fun unknownRecordingStaysUnavailableWithoutEngine() = runTest {
        val player = controller()
        player.load("missing")
        assertTrue(engines.isEmpty())
        assertFalse(player.state.value.isAvailable)
        player.play()
        assertFalse(player.state.value.isPlaying)
    }

    @Test
    fun positionFollowsEngineWhilePlaying() = runTest {
        val player = controller()
        player.load("rec")
        val engine = engines.single()
        engine.prepared!!(10_000)

        player.play()
        assertTrue(engine.playing)
        engine.position = 1_500
        advanceTimeBy(150)
        runCurrent()
        assertEquals(1_500, player.state.value.positionMs)

        player.pause()
        assertFalse(engine.playing)
        assertFalse(player.state.value.isPlaying)
    }

    @Test
    fun speedIsAppliedOnStartWhenPausedAndImmediatelyWhenPlaying() = runTest {
        val player = controller()
        player.load("rec")
        val engine = engines.single()
        engine.prepared!!(10_000)

        // На паузе движок не трогаем: PlaybackParams запустили бы воспроизведение.
        player.setSpeed(1.5f)
        assertTrue(engine.speedCalls.isEmpty())
        player.play()
        assertEquals(1.5f, engine.currentSpeed)

        player.setSpeed(2f)
        assertEquals(listOf(2f), engine.speedCalls)
        assertEquals(2f, player.state.value.speed)
    }

    @Test
    fun seekIsClampedAndCompletionRestartsFromBeginning() = runTest {
        val player = controller()
        player.load("rec")
        val engine = engines.single()
        engine.prepared!!(5_000)

        player.seekTo(9_000)
        assertEquals(5_000, engine.position)
        player.play()
        engine.completion!!()
        assertFalse(player.state.value.isPlaying)
        assertEquals(5_000, player.state.value.positionMs)

        player.play()
        assertEquals(0, engine.position)
        assertTrue(player.state.value.isPlaying)
    }

    @Test
    fun errorMakesPlayerUnavailable() = runTest {
        val player = controller()
        player.load("rec")
        engines.single().error!!()
        assertFalse(player.state.value.isAvailable)
    }

    @Test
    fun releaseOnlyForOwnRecordingAndLateCallbacksIgnored() = runTest {
        val player = controller(mapOf("a" to "content://a", "b" to "content://b"))
        player.load("a")
        val first = engines[0]
        player.load("b")
        assertTrue(first.released)
        // Поздний колбэк старого движка не должен испортить состояние новой записи.
        first.prepared!!(99_000)
        assertFalse(player.state.value.isAvailable)

        player.release("a")
        assertEquals("b", player.state.value.recordingId)
        player.release("b")
        assertTrue(engines[1].released)
        assertNull(player.state.value.recordingId)
    }
}
