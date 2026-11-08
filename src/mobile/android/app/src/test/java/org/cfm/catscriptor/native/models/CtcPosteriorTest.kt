package org.cfm.catscriptor.native.models

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class CtcPosteriorTest {
    private fun posterior(vararg best: Int) = CtcPosterior(best.size, 4, 40, 3,
        FloatArray(best.size * 4) { if (it % 4 == best[it / 4]) 0f else -10f })

    @Test fun durationsComeFromOccupiedFramesNotEvenDistribution() {
        val aligned = posterior(3, 1, 1, 3, 3, 2, 3).align(listOf(1, 2))
        assertEquals(listOf(CtcTokenFrames(1, 1, 3), CtcTokenFrames(2, 5, 6)), aligned)
    }

    @Test fun repeatedLetterRequiresBlankAndKeepsItsGap() {
        assertEquals(listOf(CtcTokenFrames(1, 0, 1), CtcTokenFrames(1, 2, 3)),
            posterior(1, 3, 1).align(listOf(1, 1)))
    }

    @Test(expected = IllegalStateException::class) fun impossibleRepeatedTargetIsRejected() {
        posterior(1, 1).align(listOf(1, 1))
    }

    @Test fun emptyRecognitionHasNoTokenIntervals() {
        assertTrue(posterior(3, 3, 3).align(emptyList()).isEmpty())
    }

    @Test(expected = CancellationException::class) fun cancellationIsObservedInsideDynamicProgramming() {
        var checks = 0
        posterior(1, 3, 2, 3, 3).align(listOf(1, 2)) {
            if (++checks == 3) throw CancellationException()
        }
    }

    @Test(expected = IllegalStateException::class) fun officialAarCannotPretendToProvideAlignment() {
        CtcPosterior.parse("request")
    }

    @Test(expected = IllegalArgumentException::class) fun untrustedShapeIsRejected() {
        CtcPosterior.parse("v1 2147483647 34 40 33 0")
    }

    @Test fun impossibleRepeatedTargetReportsRequiredFrames() {
        val error = assertThrows(IllegalStateException::class.java) {
            posterior(1, 1).align(listOf(1, 1))
        }
        assertTrue(error.message!!.contains("минимум 3 кадров, доступно 2"))
    }

    @Test fun maximumSupportedTraceDoesNotOverflow() {
        val target = List(625) { it % 2 + 1 }
        val aligned = posterior(*target.toIntArray()).align(target)
        assertEquals(625, aligned.size)
        assertEquals(CtcTokenFrames(target.last(), 624, 625), aligned.last())
    }

    @Test(expected = CancellationException::class) fun cancellationIsObservedDuringBacktrace() {
        var checks = 0
        posterior(1, 3, 2).align(listOf(1, 2)) {
            if (++checks == 4) throw CancellationException()
        }
    }

    @Test fun validPathAloneDoesNotEstablishAcousticConfidence() {
        // Равные оценки не содержат сведений о звучании, но допустимый CTC-путь существует.
        val ambiguous = CtcPosterior(10, 4, 40, 3, FloatArray(40) { -1.3862944f })
        val aligned = ambiguous.align(listOf(1))
        assertEquals(1, aligned.size)
        assertTrue(aligned.single().startFrame < aligned.single().endFrameExclusive)
    }
}
