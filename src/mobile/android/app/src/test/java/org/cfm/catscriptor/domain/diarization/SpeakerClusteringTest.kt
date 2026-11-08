package org.cfm.catscriptor.domain.diarization

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerClusteringTest {
    private val dimension = 64

    /** Голос — случайное направление; окна — шумные копии (косинус с центром ≈ 0,8). */
    private fun voices(count: Int, random: Random) = List(count) { FloatArray(dimension) { random.nextFloat() * 2 - 1 } }

    private fun noisy(center: FloatArray, random: Random, noise: Float = 0.35f): FloatArray {
        val norm = sqrt(center.sumOf { it.toDouble() * it }).toFloat()
        return FloatArray(dimension) { center[it] / norm + (random.nextFloat() * 2 - 1) * noise / sqrt(dimension.toFloat()) * 2 }
    }

    private fun windows(order: List<Int>, centers: List<FloatArray>, random: Random) =
        order.map { noisy(centers[it], random) }

    private fun assertSamePartition(expected: List<Int>, actual: IntArray) {
        assertEquals(expected.size, actual.size)
        val mapping = HashMap<Int, Int>()
        expected.indices.forEach { i ->
            val previous = mapping.putIfAbsent(expected[i], actual[i])
            assertTrue("окно $i: ожидался кластер ${mapping[expected[i]]}, получен ${actual[i]}", previous == null || previous == actual[i])
        }
        assertEquals("разные голоса не должны сливаться", expected.distinct().size, actual.distinct().size)
    }

    @Test
    fun findsNumberOfSpeakersWithoutHint() {
        val random = Random(1)
        val centers = voices(4, random)
        // Чередование реплик, как в диалоге: 4 спикера по 20 окон.
        val order = List(80) { (it / 5) % 4 }
        val labels = SpeakerClustering().cluster(windows(order, centers, random), List(80) { 750L })
        assertSamePartition(order, labels)
    }

    @Test
    fun targetCountKeepsShortSpeakerThatAutoModeAbsorbs() {
        // Третий голос звучит 2 окна: «авто» отдаёт их соседу, а заданные 3 спикера их сохраняют.
        val random = Random(3)
        val centers = voices(3, random)
        val order = List(30) { if (it < 15) 0 else 1 } + listOf(2, 2)
        val labels = SpeakerClustering().cluster(windows(order, centers, random), List(order.size) { 750L }, targetSpeakers = 3)
        assertSamePartition(order, labels)
    }

    @Test
    fun targetCountKeepsNearDuplicateVoicesApart() {
        // Близкие голоса (косинус центров > 0,8) «авто» склеивает; при заданных 3 спикерах они раздельны.
        val random = Random(4)
        val base = voices(1, random)[0]
        val twin = FloatArray(dimension) { base[it] + (random.nextFloat() * 2 - 1) * 0.1f }
        val other = voices(1, random)[0]
        val points = List(20) { noisy(base, random, 0.1f) } + List(20) { noisy(twin, random, 0.1f) } + List(20) { noisy(other, random, 0.1f) }
        val labels = SpeakerClustering().cluster(points, List(60) { 750L }, targetSpeakers = 3)
        assertEquals(3, labels.distinct().size)
        assertEquals(1, labels.takeLast(20).distinct().size)
    }

    @Test
    fun targetCountBelowRealNumberMergesVoices() {
        val random = Random(1)
        val centers = voices(4, random)
        val order = List(80) { (it / 5) % 4 }
        val labels = SpeakerClustering().cluster(windows(order, centers, random), List(80) { 750L }, targetSpeakers = 2)
        assertEquals(2, labels.distinct().size)
        // Окна одного голоса не разрываются между кластерами.
        order.distinct().forEach { voice ->
            assertEquals(1, order.indices.filter { order[it] == voice }.map { labels[it] }.distinct().size)
        }
    }

    @Test
    fun targetCountInAgglomerativeModeAndAboveWindowCount() {
        val random = Random(5)
        val centers = voices(2, random)
        val order = listOf(0, 0, 0, 1, 1, 1)
        val points = windows(order, centers, random)
        assertSamePartition(order, SpeakerClustering().cluster(points, List(6) { 1_000L }, targetSpeakers = 2))
        // Спикеров не может быть больше окон.
        assertEquals(6, SpeakerClustering().cluster(points, List(6) { 1_000L }, targetSpeakers = 10).distinct().size)
        assertEquals(setOf(0), SpeakerClustering().cluster(points, List(6) { 1_000L }, targetSpeakers = 1).toSet())
    }

    @Test
    fun targetCountWithSubsampling() {
        val random = Random(6)
        val centers = voices(3, random)
        val order = List(300) { (it / 10) % 3 }
        val labels = SpeakerClustering(SpeakerClusteringSettings(maxPoints = 60))
            .cluster(windows(order, centers, random), List(300) { 750L }, targetSpeakers = 3)
        assertSamePartition(order, labels)
    }

    @Test
    fun singleVoiceStaysOneSpeaker() {
        val random = Random(2)
        val center = voices(1, random)
        val labels = SpeakerClustering().cluster(List(40) { noisy(center[0], random) }, List(40) { 750L })
        assertEquals(setOf(0), labels.toSet())
    }

    @Test
    fun tinyClusterIsAbsorbedIntoNearestSpeaker() {
        val random = Random(3)
        val centers = voices(3, random)
        // Третий «голос» звучит 2 окна (1,5 с) — меньше minSpeakerMs, отдельным спикером не становится.
        val order = List(30) { if (it < 15) 0 else 1 } + listOf(2, 2)
        val labels = SpeakerClustering().cluster(windows(order, centers, random), List(order.size) { 750L })
        assertEquals(2, labels.distinct().size)
        assertSamePartition(order.take(30), labels.copyOfRange(0, 30))
    }

    @Test
    fun nearDuplicateClustersAreMerged() {
        val random = Random(4)
        val base = voices(1, random)[0]
        // Два «голоса» с косинусом центров > 0,8 — один человек (например, сменил громкость).
        val twin = FloatArray(dimension) { base[it] + (random.nextFloat() * 2 - 1) * 0.1f }
        val other = voices(1, random)[0]
        val points = List(20) { noisy(base, random, 0.1f) } + List(20) { noisy(twin, random, 0.1f) } + List(20) { noisy(other, random, 0.1f) }
        val labels = SpeakerClustering().cluster(points, List(60) { 750L })
        assertEquals(2, labels.distinct().size)
        assertEquals(1, labels.take(40).distinct().size)
    }

    @Test
    fun shortRecordingKeepsOneVoiceAcrossDifferentPhrases() {
        // Регрессия: диалог 12 с (9 окон, агломеративный режим). Один человек в двух репликах даёт
        // косинус ≈ 0,45 между окнами разных реплик (внутри реплики окна почти совпадают), другой
        // человек ортогонален. Порог 0,5 делил первого на двух «спикеров».
        fun vector(vararg parts: Pair<Int, Float>) = FloatArray(8).also { v -> parts.forEach { (axis, value) -> v[axis] = value } }
        val firstPhrase = vector(0 to 1f, 1 to 1.1f)
        val otherVoice = vector(3 to 1f, 4 to 0.3f)
        val secondPhrase = vector(0 to 1f, 2 to 1.1f)
        val random = Random(7)
        fun jitter(base: FloatArray) = FloatArray(8) { base[it] + (random.nextFloat() - 0.5f) * 0.02f }
        val order = listOf(0, 0, 0, 1, 1, 1, 0, 0, 0)
        val bases = listOf(firstPhrase, otherVoice, secondPhrase)
        // Каждая реплика — 3 окна (2,25 с): не меньше minSpeakerMs, поэтому не поглощается как «мелкий» кластер.
        val phrase = listOf(0, 0, 0, 1, 1, 1, 2, 2, 2)
        val labels = SpeakerClustering().cluster(phrase.map { jitter(bases[it]) }, List(9) { 750L })
        assertSamePartition(order, labels)
    }

    @Test
    fun shortInputUsesAgglomerativeMode() {
        val random = Random(5)
        val centers = voices(2, random)
        val order = listOf(0, 0, 0, 1, 1, 1)
        val labels = SpeakerClustering().cluster(windows(order, centers, random), List(6) { 1_000L })
        assertSamePartition(order, labels)
    }

    @Test
    fun longInputIsSubsampledAndRemainderAttached() {
        val random = Random(6)
        val centers = voices(3, random)
        val order = List(300) { (it / 10) % 3 }
        val settings = SpeakerClusteringSettings(maxPoints = 60)
        val labels = SpeakerClustering(settings).cluster(windows(order, centers, random), List(300) { 750L })
        assertSamePartition(order, labels)
    }

    @Test
    fun labelsAreNumberedByFirstAppearanceAndDeterministic() {
        val random = Random(7)
        val centers = voices(2, random)
        val order = List(30) { if (it % 10 < 5) 1 else 0 }
        val points = windows(order, centers, random)
        val first = SpeakerClustering().cluster(points, List(30) { 750L })
        assertEquals(0, first[0])
        assertArrayEquals(first, SpeakerClustering().cluster(points, List(30) { 750L }))
    }

    @Test
    fun eigenDecompositionOfSymmetricMatrix() {
        val random = Random(8)
        val n = 7
        val a = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in i until n) { val v = random.nextDouble() - 0.5; a[i][j] = v; a[j][i] = v }
        val eigen = SymmetricEigen(a)
        for (k in 0 until n) {
            if (k > 0) assertTrue(eigen.values[k] >= eigen.values[k - 1])
            for (i in 0 until n) {
                val av = (0 until n).sumOf { a[i][it] * eigen.vectors[it][k] }
                assertEquals(eigen.values[k] * eigen.vectors[i][k], av, 1e-9)
            }
            val norm = (0 until n).sumOf { eigen.vectors[it][k] * eigen.vectors[it][k] }
            assertEquals(1.0, norm, 1e-9)
        }
        val trace = (0 until n).sumOf { a[it][it] }
        assertTrue(abs(trace - eigen.values.sum()) < 1e-9)
    }
}
