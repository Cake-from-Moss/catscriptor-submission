package org.cfm.catscriptor.audio.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ResamplerOptimizationTest {
    private fun process(rate: Int, input: FloatArray, baseline: Boolean, splits: IntArray): FloatArray {
        val result = ArrayList<Float>()
        val emit: (FloatArray) -> Unit = { block ->
            assertTrue(block.size <= 1024)
            block.forEach { result.add(it) }
        }
        val original = if (baseline) BaselineSincResampler(rate, emit) else null
        val optimized = if (!baseline) StreamingSincResampler(rate, emit) else null
        var offset = 0
        var split = 0
        while (offset < input.size) {
            val end = minOf(input.size, offset + splits[split++ % splits.size])
            val block = input.copyOfRange(offset, end)
            original?.accept(block)
            optimized?.accept(block)
            offset = end
        }
        repeat(2) {
            original?.finish()
            optimized?.finish()
        }
        return result.toFloatArray()
    }

    @Test fun matchesFrozenBaselineAcrossPhasesBoundariesAndSplits() {
        for (rate in listOf(8000, 16000, 22050, 44100, 48000, 96000, 44117)) {
            for (length in listOf(0, 1, 2, 17, 93, 94, 95, 256, rate + 13)) {
                // Смесь частот, импульсов и значений за диапазоном проверяет также ограничение амплитуды.
                val input = FloatArray(length) {
                    if (it % 127 == 0 && rate != 16000) 1.2f else
                        (0.6 * sin(it * 0.13) + 0.3 * cos(it * 1.71)).toFloat()
                }
                val expected = process(rate, input, true, intArrayOf(maxOf(1, length)))
                assertEquals((length.toLong() * 16000 + rate / 2) / rate, expected.size.toLong())
                for (splits in listOf(intArrayOf(maxOf(1, length)), intArrayOf(1, 37, 4096, 2, 137))) {
                    assertArrayEquals("rate=$rate length=$length", expected, process(rate, input, false, splits), 0f)
                }
            }
        }
    }

    /** Запуск вручную с -Dcatscriptor.resampler.benchmark=true; скорость не является условием теста. */
    @Test fun benchmarkTenSecondsOnHost() {
        assumeTrue(java.lang.Boolean.getBoolean("catscriptor.resampler.benchmark"))
        for (rate in listOf(44100, 48000)) {
            val input = FloatArray(rate * 10) { (0.7 * sin(it * 0.13)).toFloat() }
            fun run(baseline: Boolean): Pair<Long, Long> {
                var count = 0L
                var checksum = 1L
                val emit: (FloatArray) -> Unit = { block ->
                    for (sample in block) {
                        count++
                        checksum = checksum * 31 + sample.toRawBits()
                    }
                }
                val original = if (baseline) BaselineSincResampler(rate, emit) else null
                val optimized = if (!baseline) StreamingSincResampler(rate, emit) else null
                for (offset in input.indices step 4096) {
                    val block = input.copyOfRange(offset, minOf(input.size, offset + 4096))
                    original?.accept(block)
                    optimized?.accept(block)
                }
                original?.finish()
                optimized?.finish()
                return count to checksum
            }
            repeat(3) { assertEquals(run(true), run(false)) }
            val baselineTimes = ArrayList<Double>()
            val optimizedTimes = ArrayList<Double>()
            var expected: Pair<Long, Long>? = null
            repeat(5) { iteration ->
                for (baseline in if (iteration % 2 == 0) listOf(true, false) else listOf(false, true)) {
                    val start = System.nanoTime()
                    val result = run(baseline)
                    val milliseconds = (System.nanoTime() - start) / 1e6
                    if (expected == null) expected = result else assertEquals(expected, result)
                    (if (baseline) baselineTimes else optimizedTimes).add(milliseconds)
                }
            }
            println("HOST JVM rate=$rate seconds=10 warmup=3 runs=5 " +
                "baselineMedianMs=${baselineTimes.sorted()[2]} optimizedMedianMs=${optimizedTimes.sorted()[2]} " +
                "count/checksum=$expected (includes construction, mono input, not phone RTF)")
        }
    }
}

/** Замороженный исходный алгоритм для проверки оптимизации. */
private class BaselineSincResampler(
    private val inputSampleRate: Int,
    private val emit: (FloatArray) -> Unit,
) {
    init {
        require(inputSampleRate in 1..768_000)
    }
    private val cutoff = min(1.0, 16000.0 / inputSampleRate) * 0.94
    private val radius = ceil(32.0 / cutoff).toInt()
    private val ring = FloatArray(radius * 2 + 8)
    private var received = 0L
    private var outputIndex = 0L
    private var finished = false
    private val output = FloatArray(1024)
    private var outputSize = 0

    fun accept(mono: FloatArray) {
        check(!finished)
        if (inputSampleRate == 16000) {
            for (sample in mono) {
                require(sample.isFinite() && sample in -1f..1f)
                output[outputSize++] = sample
                received++
                if (outputSize == output.size) flush()
            }
            return
        }
        for (sample in mono) {
            require(sample.isFinite())
            ring[(received % ring.size).toInt()] = sample.coerceIn(-1f, 1f)
            received++
            drain(false)
        }
    }

    fun finish() {
        if (finished) return
        finished = true
        if (inputSampleRate != 16000) drain(true)
        flush()
    }

    private fun drain(final: Boolean) {
        val target = (received / inputSampleRate) * 16000 +
            ((received % inputSampleRate) * 16000 + inputSampleRate / 2) / inputSampleRate
        while (true) {
            // Целочисленная фаза не накапливает погрешность между блоками.
            val position = (outputIndex / 16000) * inputSampleRate +
                (outputIndex % 16000) * inputSampleRate / 16000
            val fraction = ((outputIndex % 16000) * inputSampleRate % 16000) / 16000.0
            if (final) {
                if (outputIndex >= target) break
            } else if (position + radius >= received) {
                break
            }
            var value = 0.0
            var weightSum = 0.0
            for (offset in -radius..radius) {
                val distance = offset - fraction
                if (abs(distance) >= radius) continue
                val x = PI * cutoff * distance
                val sinc = if (abs(x) < 1e-12) 1.0 else sin(x) / x
                val weight = cutoff * sinc * (0.42 + 0.5 * cos(PI * distance / radius) +
                    0.08 * cos(2 * PI * distance / radius))
                val index = position + offset
                if (index in 0 until received) {
                    check(index >= received - ring.size)
                    value += ring[(index % ring.size).toInt()] * weight
                }
                weightSum += weight
            }
            output[outputSize++] = (value / weightSum).toFloat().coerceIn(-1f, 1f)
            outputIndex++
            if (outputSize == output.size) flush()
        }
    }

    private fun flush() {
        if (outputSize > 0) emit(output.copyOf(outputSize))
        outputSize = 0
    }
}
