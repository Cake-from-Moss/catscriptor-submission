package org.cfm.catscriptor.audio.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AudioCoreTest {
    @Test fun streamsTenMinutesAtTargetRateWithoutRetainingOutput() {
        val length = 16000L * 600
        var received = 0L
        val resampler = StreamingSincResampler(16000) { block ->
            assertTrue(block.size <= 1024)
            for (sample in block) {
                assertEquals(((received % 1024) / 1024f), sample, 0f)
                received++
            }
        }
        val input = FloatArray(1024) { it / 1024f }
        var sent = 0L
        while (sent < length) {
            val count = minOf(input.size.toLong(), length - sent).toInt()
            resampler.accept(if (count == input.size) input else input.copyOf(count))
            sent += count
        }
        resampler.finish()
        assertEquals(length, received)
    }

    @Test fun streamsThirtySecondsWithIdenticalChecksumAcrossBlocks() {
        fun process(blockSize: Int): Pair<Long, Long> {
            var count = 0L
            var checksum = 1L
            val resampler = StreamingSincResampler(44100) { block ->
                assertTrue(block.size <= 1024)
                for (sample in block) {
                    checksum = checksum * 31 + sample.toRawBits()
                    count++
                }
            }
            val length = 44100 * 30
            var offset = 0
            while (offset < length) {
                val countInput = minOf(blockSize, length - offset)
                resampler.accept(FloatArray(countInput) { sin((offset + it) * 0.13).toFloat() })
                offset += countInput
            }
            resampler.finish()
            return count to checksum
        }
        val first = process(4096)
        assertEquals(480000L, first.first)
        assertEquals(first, process(137))
    }

    @Test fun cancellationInterruptsUnknownWavChunks() {
        var checks = 0
        val cancellation = java.util.concurrent.CancellationException("cancel")
        try {
            WavReader.decode(ByteArrayInputStream(wav()), {}, {}, {
                checks++
                if (checks == 3) throw cancellation
            })
            fail("Cancellation was swallowed")
        } catch (caught: java.util.concurrent.CancellationException) {
            assertSame(cancellation, caught)
        }
    }

    private fun resample(rate: Int, input: FloatArray, block: Int): FloatArray {
        val result = ArrayList<Float>()
        val resampler = StreamingSincResampler(rate) { values ->
            assertTrue(values.size <= 1024)
            values.forEach { result.add(it) }
        }
        var offset = 0
        while (offset < input.size) {
            val end = minOf(offset + block, input.size)
            resampler.accept(input.copyOfRange(offset, end))
            offset = end
        }
        resampler.finish()
        resampler.finish()
        return result.toFloatArray()
    }

    @Test fun ratesLengthsAndBlockInvariance() {
        for (rate in listOf(8000, 16000, 22050, 44100, 48000, 96000)) {
            for (length in listOf(0, 1, 17, rate + 13)) {
                val input = FloatArray(length) { sin(it * 0.13).toFloat() }
                val whole = resample(rate, input, maxOf(1, length))
                assertEquals((length.toLong() * 16000 + rate / 2) / rate, whole.size.toLong())
                assertArrayEquals(whole, resample(rate, input, 37), 0f)
            }
        }
    }

    @Test fun suppressesAliasingInShortAndLongSignals() {
        for (length in listOf(4800, 96000)) {
            fun energy(frequency: Int): Double {
                val input = FloatArray(length) { sin(2 * PI * frequency * it / 48000).toFloat() }
                val output = resample(48000, input, 137)
                return sqrt(output.drop(100).dropLast(100).map { it.toDouble() * it }.average())
            }
            assertTrue(energy(1000) > 0.65)
            assertTrue(energy(12000) < 0.005)
        }
    }

    @Test fun averagesChannelsAndDecodesIntegerWidths() {
        assertArrayEquals(floatArrayOf(-1f, 0f, 127 / 128f),
            PcmConverter.toMono(byteArrayOf(0, 128.toByte(), 255.toByte()), 3, 1, 8), 0f)
        for (bits in listOf(16, 24, 32)) {
            val buffer = ByteBuffer.allocate(2 * bits / 8).order(ByteOrder.LITTLE_ENDIAN)
            val low = -(1L shl (bits - 1))
            val high = (1L shl (bits - 1)) - 1
            for (value in listOf(low, high)) repeat(bits / 8) { buffer.put((value shr (it * 8)).toByte()) }
            assertEquals(0f, PcmConverter.toMono(buffer.array(), 1, 2, bits)[0], 0.00002f)
        }
        val floats = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(-1f).putFloat(0.5f).putFloat(0.5f).array()
        assertEquals(0f, PcmConverter.toMono(floats, 1, 3, 32, true)[0], 0f)
    }

    private fun wav(): ByteArray {
        val bytes = ByteArrayOutputStream()
        fun tag(text: String) { bytes.write(text.toByteArray(Charsets.US_ASCII)) }
        fun number(value: Int, width: Int) { repeat(width) { bytes.write(value ushr (it * 8)) } }
        tag("RIFF"); number(50, 4); tag("WAVE")
        tag("JUNK"); number(1, 4); number(42, 1); number(0, 1)
        tag("fmt "); number(16, 4); number(1, 2); number(2, 2)
        number(48000, 4); number(192000, 4); number(4, 2); number(16, 2)
        tag("data"); number(4, 4); number(-32768, 2); number(32767, 2)
        return bytes.toByteArray()
    }

    @Test fun wavSkipsUnknownChunksAndRejectsCorruption() {
        var frames = 0
        WavReader.decode(ByteArrayInputStream(wav()), {
            assertEquals(WavFormat(48000, 2, 16, false), it)
        }, { frames += it.size; assertEquals(0f, it[0], 0.00002f) })
        assertEquals(1, frames)
        val valid = wav()
        for (length in 0 until valid.size) {
            try {
                WavReader.decode(ByteArrayInputStream(valid.copyOf(length)), {}, {})
                fail("Accepted truncated WAV at $length")
            } catch (_: IllegalArgumentException) { } catch (_: java.io.EOFException) { }
        }
        val invalid = valid.copyOf().also { it[50] = 3 }
        try {
            WavReader.decode(ByteArrayInputStream(invalid), {}, {})
            fail("Accepted partial frame")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun wavFloatAndUnsupportedEncoding() {
        val floating = wav().also {
            it[30] = 3
            it[32] = 1
            it[44] = 32
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(54, 0.25f)
        }
        WavReader.decode(ByteArrayInputStream(floating), {
            assertTrue(it.floatingPoint)
        }, { assertArrayEquals(floatArrayOf(0.25f), it, 0f) })
        val unsupported = wav().also { it[30] = 6 }
        try {
            WavReader.decode(ByteArrayInputStream(unsupported), {}, {})
            fail("Accepted unsupported encoding")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun malformedOwnedPcmIsCleanedUp() {
        val file = File.createTempFile("pcm-core-invalid", ".pcm")
        file.writeBytes(byteArrayOf(1))
        try {
            FilePcmAudioSource(file)
            fail("Accepted truncated float")
        } catch (_: IllegalArgumentException) {
            assertFalse(file.exists())
        } finally { file.delete() }
    }

    @Test fun fileSourceRandomAccessBoundedReadsAndOwnership() = runBlocking {
        val file = File.createTempFile("pcm-core", ".pcm")
        try {
            file.outputStream().use { output ->
                val buffer = ByteBuffer.allocate(20000 * 4).order(ByteOrder.LITTLE_ENDIAN)
                repeat(20000) { buffer.putFloat(it / 20000f) }
                output.write(buffer.array())
            }
            val source = FilePcmAudioSource(file)
            for ((start, maximum) in listOf(-1L to 1, 0L to 0, 0L to -1)) {
                try {
                    source.read(start, maximum)
                    fail("Accepted invalid read arguments")
                } catch (_: IllegalArgumentException) { }
            }
            assertEquals(20000L, source.sampleCount)
            assertEquals(16384, source.read(0, Int.MAX_VALUE).size)
            assertArrayEquals(floatArrayOf(0.5f), source.read(10000, 1), 0f)
            assertEquals(0, source.read(Long.MAX_VALUE, 1).size)
            source.close(); source.close()
            assertFalse(file.exists())
            try { source.read(0, 1); fail("Read after close") } catch (_: IllegalStateException) { }
            file.writeBytes(ByteArray(0))
            FilePcmAudioSource(file, false).close()
            assertTrue(file.exists())
        } finally { file.delete() }
    }
}
