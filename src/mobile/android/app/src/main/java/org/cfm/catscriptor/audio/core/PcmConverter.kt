package org.cfm.catscriptor.audio.core

object PcmConverter {
    /** Little-endian PCM; каналы усредняются без суммирования с переполнением. */
    fun toMono(
        bytes: ByteArray,
        frames: Int,
        channels: Int,
        bitsPerSample: Int,
        floatingPoint: Boolean = false,
    ): FloatArray {
        require(channels in 1..256 && frames >= 0)
        require(bitsPerSample in listOf(8, 16, 24, 32))
        require(!floatingPoint || bitsPerSample == 32)
        val width = bitsPerSample / 8
        require(frames.toLong() * channels * width <= bytes.size)
        var offset = 0
        return FloatArray(frames) {
            var sum = 0.0
            repeat(channels) {
                var raw = 0
                repeat(width) { b -> raw = raw or ((bytes[offset++].toInt() and 255) shl (8 * b)) }
                val sample = when {
                    floatingPoint -> Float.fromBits(raw).also { require(it.isFinite()) }.toDouble()
                    width == 1 -> (raw - 128) / 128.0
                    else -> (raw shl (32 - bitsPerSample) shr (32 - bitsPerSample)).toDouble() /
                        (1L shl (bitsPerSample - 1))
                }
                sum += sample.coerceIn(-1.0, 1.0)
            }
            (sum / channels).toFloat()
        }
    }
}
