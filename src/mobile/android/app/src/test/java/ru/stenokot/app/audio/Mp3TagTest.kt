package ru.stenokot.app.audio

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор служебного кадра MP3. */
class Mp3TagTest {
    private fun frame(marker: String, frames: Int, bytes: Int): ByteArray {
        val data = ByteBuffer.allocate(1024)
        data.put(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00))
        data.position(36)
        data.put(marker.toByteArray(Charsets.US_ASCII))
        data.putInt(3)
        data.putInt(frames)
        data.putInt(bytes)
        return data.array()
    }

    private fun id3(size: Int): ByteArray {
        val head = ByteArray(10 + size)
        "ID3".toByteArray().copyInto(head)
        head[3] = 3
        head[6] = ((size shr 21) and 0x7F).toByte()
        head[7] = ((size shr 14) and 0x7F).toByte()
        head[8] = ((size shr 7) and 0x7F).toByte()
        head[9] = (size and 0x7F).toByte()
        return head
    }

    private fun file(vararg parts: ByteArray): File {
        val out = File.createTempFile("tag", ".mp3")
        out.deleteOnExit()
        out.outputStream().use { stream -> parts.forEach(stream::write) }
        return out
    }

    @Test
    fun `кадр Xing даёт длительность и размер`() {
        val tag = Mp3Tag.read(file(frame("Xing", 1000, 500_000)))
        assertNotNull(tag)
        assertEquals(26_122L, tag!!.durationMs)
        assertEquals(500_000L, tag.bytes)
    }

    @Test
    fun `большой ID3 с обложкой пропускается`() {
        val tag = Mp3Tag.read(file(id3(300_000), frame("Info", 10, 4_000)))
        assertEquals(300_010L, tag!!.audioStart)
        assertEquals(10L, tag.frames)
    }

    @Test
    fun `обрезанный файл определяется по размеру из заголовка`() {
        val tag = Mp3Tag.read(file(frame("Xing", 1000, 500_000)))!!
        assertTrue(tag.isTruncated(200_000))
        assertFalse(tag.isTruncated(500_000))
    }

    @Test
    fun `MP3 без служебного кадра не даёт надёжной длительности`() {
        assertNull(Mp3Tag.read(file(frame("XXXX", 1, 1))))
    }

    @Test
    fun `посторонний файл не принимается за MP3`() {
        assertNull(Mp3Tag.read(file(ByteArray(4096) { 0x41 })))
    }
}
