package org.cfm.catscriptor.recording

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class WavPcmWriterTest {
    @Test fun writesPcmAndFinalHeaderWithoutKeepingWholeRecording() {
        val file = File.createTempFile("pcm-writer", ".wav")
        try {
            val writer = WavPcmWriter(file)
            repeat(100) { writer.write(shortArrayOf(-32768, 0, 32767), 3) }
            writer.close()
            writer.close()
            val bytes = file.readBytes()
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(644, bytes.size)
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals("WAVE", String(bytes, 8, 4))
            assertEquals(636, header.getInt(4))
            assertEquals(16000, header.getInt(24))
            assertEquals(32000, header.getInt(28))
            assertEquals(600, header.getInt(40))
            assertEquals((-32768).toShort(), header.getShort(44))
            assertEquals(32767.toShort(), header.getShort(48))
        } finally { file.delete() }
    }
}
