package org.cfm.catscriptor.recording

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Потоковый PCM16 mono 16 кГц; заголовок финализируется без загрузки файла в память. */
internal class WavPcmWriter(private val file: File) : Closeable {
    private val stream = FileOutputStream(file)
    private val output = stream.buffered(32 * 1024)
    private val bytes = ByteArray(1024)
    var samples = 0L
        private set
    private var closed = false
    init { output.write(ByteArray(44)) }

    fun write(pcm: ShortArray, count: Int) {
        check(!closed)
        require(count in 0..512 && count <= pcm.size)
        check((samples + count) * 2 <= 0xffffffffL - 36) { "Достигнут предел размера WAV" }
        for (index in 0 until count) {
            bytes[index * 2] = pcm[index].toByte()
            bytes[index * 2 + 1] = (pcm[index].toInt() shr 8).toByte()
        }
        output.write(bytes, 0, count * 2)
        samples += count
    }

    override fun close() {
        if (closed) return
        closed = true
        try { output.flush(); stream.fd.sync() } finally { output.close() }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt((36 + samples * 2).toInt())
        header.put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        header.putInt(16000).putInt(32000).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt((samples * 2).toInt())
        RandomAccessFile(file, "rw").use { it.write(header.array()) }
    }
}
