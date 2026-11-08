package org.cfm.catscriptor.audio.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Потоковый sinc-фильтр; длина округляется к ближайшему целому, половины вверх. */
class StreamingSincResampler(
    private val inputSampleRate: Int,
    private val emit: (FloatArray) -> Unit,
) {
    init {
        require(inputSampleRate in 1..768_000)
    }
    private val cutoff = min(1.0, 16000.0 / inputSampleRate) * 0.94
    private val radius = ceil(32.0 / cutoff).toInt()
    private val phaseStep = gcd(inputSampleRate, 16000)
    private val tapCount = radius * 2 + 1
    // Не более 4 МиБ коэффициентов; неудобные взаимно простые частоты идут старым путём.
    private val phases = (16000 / phaseStep).let { count ->
        if (inputSampleRate == 16000 || count.toLong() * (tapCount + 1) * 8 > 4 * 1024 * 1024) {
            null
        } else {
            Array(count) { phase ->
                val fraction = phase * phaseStep / 16000.0
                DoubleArray(tapCount + 1).also { weights ->
                    var sum = 0.0
                    for (tap in 0 until tapCount) {
                        val weight = weight(tap - radius - fraction)
                        weights[tap] = weight
                        sum += weight
                    }
                    weights[tapCount] = sum
                }
            }
        }
    }
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
            val weights = phases?.get((((outputIndex % 16000) * inputSampleRate) % 16000 / phaseStep).toInt())
            var weightSum = weights?.get(tapCount) ?: 0.0
            for (offset in -radius..radius) {
                val distance = offset - fraction
                if (abs(distance) >= radius) continue
                val weight = weights?.get(offset + radius) ?: weight(distance)
                val index = position + offset
                if (index in 0 until received) {
                    check(index >= received - ring.size)
                    value += ring[(index % ring.size).toInt()] * weight
                }
                if (weights == null) weightSum += weight
            }
            output[outputSize++] = (value / weightSum).toFloat().coerceIn(-1f, 1f)
            outputIndex++
            if (outputSize == output.size) flush()
        }
    }

    private fun weight(distance: Double): Double {
        if (abs(distance) >= radius) return 0.0
        val x = PI * cutoff * distance
        val sinc = if (abs(x) < 1e-12) 1.0 else sin(x) / x
        return cutoff * sinc * (0.42 + 0.5 * cos(PI * distance / radius) +
            0.08 * cos(2 * PI * distance / radius))
    }

    private fun gcd(first: Int, second: Int): Int {
        var a = first
        var b = second
        while (b != 0) {
            val remainder = a % b
            a = b
            b = remainder
        }
        return a
    }

    private fun flush() {
        if (outputSize > 0) emit(output.copyOf(outputSize))
        outputSize = 0
    }
}
