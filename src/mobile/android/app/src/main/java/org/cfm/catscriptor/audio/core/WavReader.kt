package org.cfm.catscriptor.audio.core

import java.io.EOFException
import java.io.File
import java.io.InputStream

data class WavFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val floatingPoint: Boolean,
)

object WavReader {
    /** Закрывает только самостоятельно открытый поток; исключения обработчиков не перехватываются. */
    fun decode(file: File, onFormat: (WavFormat) -> Unit, onMonoBlock: (FloatArray) -> Unit) {
        decode(file, onFormat, onMonoBlock, {})
    }

    fun decode(
        file: File,
        onFormat: (WavFormat) -> Unit,
        onMonoBlock: (FloatArray) -> Unit,
        checkCancellation: () -> Unit,
    ) {
        checkCancellation()
        file.inputStream().buffered().use { decode(it, onFormat, onMonoBlock, checkCancellation) }
    }

    /** Читает RIFF без закрытия входа; каждый блок содержит не более 4096 кадров. */
    fun decode(input: InputStream, onFormat: (WavFormat) -> Unit, onSamples: (FloatArray) -> Unit) {
        decode(input, onFormat, onSamples, {})
    }

    fun decode(
        input: InputStream,
        onFormat: (WavFormat) -> Unit,
        onSamples: (FloatArray) -> Unit,
        checkCancellation: () -> Unit,
    ) {
        checkCancellation()
        val header = input.exact(12)
        require(header.tag(0) == "RIFF" && header.tag(8) == "WAVE") { "Unsupported WAV container" }
        var remaining = header.u32(4) - 4
        require(remaining >= 0)
        var format: WavFormat? = null
        var foundData = false
        while (remaining > 0) {
            checkCancellation()
            require(remaining >= 8) { "Truncated RIFF chunk header" }
            val chunk = input.exact(8)
            val size = chunk.u32(4)
            remaining -= 8
            val padded = size + (size and 1)
            require(padded <= remaining) { "Chunk exceeds RIFF boundary" }
            when (chunk.tag(0)) {
                "fmt " -> {
                    require(format == null && !foundData && size >= 16) { "Invalid WAV format chunk" }
                    val bytes = input.exact(minOf(size, 40).toInt())
                    input.discard(size - bytes.size, checkCancellation)
                    var encoding = bytes.u16(0)
                    val channels = bytes.u16(2)
                    val rate = bytes.u32(4)
                    val bits = bytes.u16(14)
                    if (encoding == 0xfffe) {
                        require(size >= 40 && bytes.u16(16) >= 22 && bytes.u16(18) == bits) {
                            "Unsupported extensible WAV valid bits"
                        }
                        val guidTail = byteArrayOf(0, 0, 0, 0, 16, 0, 128.toByte(), 0, 0, 170.toByte(), 0, 56, 155.toByte(), 113)
                        require(bytes.copyOfRange(26, 40).contentEquals(guidTail)) { "Unsupported WAV subformat" }
                        encoding = bytes.u16(24)
                    }
                    require(encoding == 1 || encoding == 3) { "Unsupported WAV encoding: $encoding" }
                    require(channels in 1..256 && rate in 1..768000)
                    require(bits in listOf(8, 16, 24, 32) && (encoding != 3 || bits == 32))
                    val alignment = channels * (bits / 8)
                    require(bytes.u16(12) == alignment && bytes.u32(8) == rate * alignment) {
                        "Invalid WAV block alignment or byte rate"
                    }
                    format = WavFormat(rate.toInt(), channels, bits, encoding == 3)
                    onFormat(format)
                }
                "data" -> {
                    val current = requireNotNull(format) { "WAV data precedes format" }
                    val frameSize = current.channels * current.bitsPerSample / 8
                    require(size % frameSize == 0L) { "Truncated WAV frame" }
                    var left = size
                    while (left > 0) {
                        checkCancellation()
                        val count = minOf(left / frameSize, 4096).toInt()
                        val bytes = input.exact(count * frameSize)
                        onSamples(PcmConverter.toMono(bytes, count, current.channels,
                            current.bitsPerSample, current.floatingPoint))
                        left -= bytes.size
                    }
                    foundData = true
                }
                else -> input.discard(size, checkCancellation)
            }
            if (size and 1L != 0L) input.exact(1)
            remaining -= padded
        }
        require(format != null && foundData) { "Missing WAV format or data" }
    }

    private fun ByteArray.tag(offset: Int) = String(this, offset, 4, Charsets.US_ASCII)
    private fun ByteArray.u16(offset: Int) = (this[offset].toInt() and 255) or
        ((this[offset + 1].toInt() and 255) shl 8)
    private fun ByteArray.u32(offset: Int) = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
    private fun InputStream.exact(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(bytes, offset, count - offset)
            if (read < 0) throw EOFException("Truncated WAV")
            if (read == 0) {
                val next = read()
                if (next < 0) throw EOFException("Truncated WAV")
                bytes[offset++] = next.toByte()
            } else offset += read
        }
        return bytes
    }
    private fun InputStream.discard(count: Long, checkCancellation: () -> Unit) {
        var left = count
        val buffer = ByteArray(8192)
        while (left > 0) {
            checkCancellation()
            val read = read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (read < 0) throw EOFException("Truncated WAV chunk")
            if (read > 0) left -= read else {
                if (read() < 0) throw EOFException("Truncated WAV chunk")
                left--
            }
        }
    }
}
