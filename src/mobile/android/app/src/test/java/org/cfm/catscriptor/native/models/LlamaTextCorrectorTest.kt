package org.cfm.catscriptor.native.models

import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.cfm.catscriptor.domain.pipeline.CorrectionGuard
import org.cfm.catscriptor.domain.pipeline.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Корректор поверх фейкового нативного бриджа: метаданные, фолбэки, отмена, освобождение. */
@OptIn(ExperimentalCoroutinesApi::class)
class LlamaTextCorrectorTest {
    @get:Rule val temporary = TemporaryFolder()

    /** Блокировка генерации до внешнего сигнала: started — генерация началась, release — отпустить. */
    private class GenerationGate(val started: java.util.concurrent.CountDownLatch, val release: java.util.concurrent.CountDownLatch)

    private class FakeBridge : LlamaBridge {
        var created = 0
        var loaded = 0
        var unloaded = 0
        var cancels = 0
        val userPrompts = mutableListOf<String>()
        val maxTokens = mutableListOf<Int>()
        val penalties = mutableListOf<Float>()
        val responses = ArrayDeque<String>()
        var failure: Throwable? = null
        var gate: GenerationGate? = null

        override fun createSession(): Long {
            created++
            return 7L
        }

        override fun loadModel(sessionId: Long, modelPath: String, contextSize: Int, threads: Int) {
            loaded++
        }

        override fun generate(
            sessionId: Long,
            systemPrompt: String,
            userPrompt: String,
            maxTokens: Int,
            temperature: Float,
            topP: Float,
            topK: Int,
            presencePenalty: Float,
        ): String {
            userPrompts += userPrompt
            this.maxTokens += maxTokens
            penalties += presencePenalty
            failure?.let { throw it }
            gate?.let {
                it.started.countDown()
                it.release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            }
            return responses.removeFirstOrNull() ?: ""
        }

        override fun cancel(sessionId: Long) {
            cancels++
            gate?.release?.countDown()
        }

        override fun unload(sessionId: Long) {
            unloaded++
        }
    }

    private fun corrector(
        bridge: FakeBridge,
        responses: List<String>,
        failure: Throwable? = null,
    ): LlamaTextCorrector {
        bridge.responses.addAll(responses)
        bridge.failure = failure
        return LlamaTextCorrector(
            openModel = { File(temporary.newFolder(), "model.gguf") },
            config = LlamaConfig(),
            bridge = bridge,
            dispatcher = UnconfinedTestDispatcher(),
        )
    }

    private val utterance = Utterance("speaker_1", 1250, 4380, "ну давай встретимся завтра в пятницу")

    @Test fun appliesSafeCorrectionAndKeepsMetadata() = runTest {
        val bridge = FakeBridge()
        val sut = corrector(bridge, listOf("Ну, давай встретимся завтра, в пятницу."))

        val text = sut.correct(utterance)

        assertEquals("Ну, давай встретимся завтра, в пятницу.", text)
        assertEquals("speaker_1", utterance.speakerId)
        assertEquals(1250, utterance.startMs)
        assertEquals(4380, utterance.endMs)
    }

    @Test fun droppedWordIsRestoredInsteadOfLost() = runTest {
        // Реальный ответ Qwen2.5-1.5B: «только сначала» потеряно; прежний guard это пропускал.
        val bridge = FakeBridge()
        val sut = corrector(bridge, listOf("Хорошо, только найди его любимую мышку. Она где-то под диваном."))
        val text = sut.correct(utterance.copy(text = "хорошо только сначала найди его любимую мышку она где то под диваном"))
        assertEquals("Хорошо, только сначала найди его любимую мышку. Она где-то под диваном.", text)
    }

    @Test fun longUtteranceIsCorrectedInChunksWithoutTruncation() = runTest {
        val bridge = FakeBridge()
        val words = (1..100).map { "слово" + "абвгдежзиклмнопрстуф"[it % 20] + "к" }
        // Модель вернула фрагменты как есть: итог — исходный текст целиком, без потерянного хвоста.
        val sut = corrector(bridge, emptyList())
        bridge.responses.addAll(listOf(words.take(34), words.subList(34, 68), words.drop(68)).map { it.joinToString(" ") })
        val text = sut.correct(utterance.copy(text = words.joinToString(" ")))
        assertEquals(3, bridge.userPrompts.size)
        assertEquals(listOf(34, 34, 32), bridge.userPrompts.map { it.split(" ").size })
        assertEquals(words.joinToString(" ").lowercase(), text.lowercase())
        assertTrue(bridge.maxTokens.all { it >= 34 * 4 })
    }

    @Test fun chunkBoundaryDoesNotBreakSentence() = runTest {
        val bridge = FakeBridge()
        val words = (1..50).map { "слово" + "абвгдежзиклмнопрстуф"[it % 20] + "к" }
        // Модель закрыла каждый фрагмент точкой и начала с заглавной — как настоящая Qwen.
        val sut = corrector(bridge, emptyList())
        bridge.responses.addAll(listOf(words.take(25), words.drop(25)).map { it.joinToString(" ").replaceFirstChar(Char::uppercaseChar) + "." })
        val text = sut.correct(utterance.copy(text = words.joinToString(" ")))
        val expected = words.joinToString(" ").replaceFirstChar(Char::uppercaseChar) + "."
        assertEquals(expected, text)
    }

    @Test fun presencePenaltyIsOffByDefault() = runTest {
        val bridge = FakeBridge()
        corrector(bridge, listOf("Ну, давай встретимся завтра, в пятницу.")).correct(utterance)
        assertEquals(listOf(0f), bridge.penalties)
    }

    @Test fun loadsModelOnceForManyUtterances() = runTest {
        val bridge = FakeBridge()
        val sut = corrector(bridge, listOf("Да.", "Нет, не приду."))

        sut.correct(utterance)
        sut.correct(utterance.copy(text = "да нет не приду"))

        assertEquals(1, bridge.created)
        assertEquals(1, bridge.loaded)
        assertEquals(2, bridge.userPrompts.size)
        // Контекст между репликами не протекает: в каждый промпт — только её текст.
        assertEquals("ну давай встретимся завтра в пятницу", bridge.userPrompts[0])
        assertEquals("да нет не приду", bridge.userPrompts[1])
    }

    @Test fun blankOriginalReturnsBlankWithoutTouchingModel() = runTest {
        val bridge = FakeBridge()
        val sut = corrector(bridge, emptyList())

        val text = sut.correct(utterance.copy(text = "   "))

        assertEquals("   ", text)
        assertEquals(0, bridge.created)
        assertEquals(0, bridge.userPrompts.size)
    }

    @Test fun blankModelAnswerFallsBackToOriginal() = runTest {
        val bridge = FakeBridge()
        val sut = corrector(bridge, listOf("   "))

        val text = sut.correct(utterance)

        assertEquals(utterance.text, text)
    }

    @Test fun hallucinatedAnswerFallsBackToOriginal() = runTest {
        val bridge = FakeBridge()
        val sut = corrector(bridge, listOf("Хорошо, предлагаю встретиться вечером, а потом еще погуляем."))

        val text = sut.correct(utterance)

        assertEquals(utterance.text, text)
    }

    @Test fun nativeErrorPropagatesAndReleasesResources() = runTest {
        val bridge = FakeBridge()
        val sut = corrector(bridge, emptyList(), failure = RuntimeException("native boom"))

        try {
            sut.correct(utterance)
            fail("ожидалась ошибка native backend")
        } catch (error: RuntimeException) {
            assertEquals("native boom", error.message)
        }

        assertEquals(1, bridge.unloaded)
        // После ошибки ресурс закрыт: повторный вызов запрещён.
        try {
            sut.correct(utterance)
            fail("ожидалось IllegalStateException после закрытия")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("закрыт"))
        }
    }

    @Test fun cancellationStopsNativeGenerationAndReleasesResources() =
        kotlinx.coroutines.runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val bridge = FakeBridge()
        bridge.gate = GenerationGate(started, release)
        // Как в проде: генерация блокирует поток фонового пула, отмена приходит с другого потока.
        val sut = LlamaTextCorrector(
            openModel = { File(temporary.newFolder(), "model.gguf") },
            config = LlamaConfig(),
            bridge = bridge,
            dispatcher = kotlinx.coroutines.Dispatchers.Default,
        )
        var cancelled = false
        val job = launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                sut.correct(utterance)
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            }
        }
        assertTrue("генерация должна начаться", started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        job.cancel()
        job.join()

        assertTrue(cancelled)
        assertTrue("отмена должна дойти до нативного бриджа", bridge.cancels >= 1)
        assertEquals(1, bridge.unloaded)
    }

    @Test fun closeReleasesModelAndDoubleCloseIsSafe() = runTest {
        val bridge = FakeBridge()
        val sut = corrector(bridge, listOf("Ну, давай встретимся завтра, в пятницу."))
        sut.correct(utterance)

        sut.close()
        sut.close()

        assertEquals(1, bridge.unloaded)
    }

    @Test fun validationRejectsBrokenConfig() {
        val bridge = FakeBridge()
        try {
            LlamaTextCorrector(
                openModel = { File("model.gguf") },
                config = LlamaConfig(threads = 0),
                bridge = bridge,
                dispatcher = UnconfinedTestDispatcher(),
            )
            fail("ожидалась ошибка конфигурации")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("потоков"))
        }
    }

    @Test fun openModelCancellationPreventsNativeLoad() = runTest {
        val bridge = FakeBridge()
        var calls = 0
        val sut = LlamaTextCorrector(
            openModel = {
                calls++
                throw CancellationException()
            },
            config = LlamaConfig(),
            bridge = bridge,
            dispatcher = UnconfinedTestDispatcher(),
        )

        try {
            sut.correct(utterance)
            fail("ожидалась отмена")
        } catch (error: CancellationException) {
            // ожидаемо
        }

        assertEquals(1, calls)
        // Сессия создана до загрузки, сама модель не загружалась; ресурс освобождён.
        assertEquals(1, bridge.created)
        assertEquals(0, bridge.loaded)
        assertEquals(1, bridge.unloaded)
    }
}
