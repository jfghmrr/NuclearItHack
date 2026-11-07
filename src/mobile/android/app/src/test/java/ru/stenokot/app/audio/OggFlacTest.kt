package ru.stenokot.app.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import ru.stenokot.core.audio.FileAudioInput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Распаковка FLAC из страниц Ogg. */
class OggFlacTest {
    private val streamInfo = ByteArray(34).also {
        it[10] = 0x03
        it[11] = 0xE8.toByte()
        it[12] = 0x00
    }

    private fun signaturePacket(): ByteArray {
        val packet = ByteArrayOutputStream()
        packet.write(byteArrayOf(0x7F) + "FLAC".toByteArray() + byteArrayOf(1, 0, 0, 1))
        packet.write("fLaC".toByteArray())
        packet.write(byteArrayOf(0, 0, 0, 34))
        packet.write(streamInfo)
        return packet.toByteArray()
    }

    private fun page(packets: List<ByteArray>, granule: Long, continued: Boolean = false): ByteArray {
        val lacing = ByteArrayOutputStream()
        val body = ByteArrayOutputStream()
        for (packet in packets) {
            var left = packet.size
            while (left >= 255) {
                lacing.write(255)
                left -= 255
            }
            lacing.write(left)
            body.write(packet)
        }
        val head = ByteBuffer.allocate(27 + lacing.size()).order(ByteOrder.LITTLE_ENDIAN)
        head.put("OggS".toByteArray())
        head.put(0)
        head.put(if (continued) 1 else 0)
        head.putLong(granule)
        head.putInt(77)
        head.putInt(0)
        head.putInt(0)
        head.put(lacing.size().toByte())
        head.put(lacing.toByteArray())
        return head.array() + body.toByteArray()
    }

    private fun file(vararg pages: ByteArray): File {
        val out = File.createTempFile("flac", ".ogg")
        out.deleteOnExit()
        out.outputStream().use { stream -> pages.forEach(stream::write) }
        return out
    }

    private val frameA = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 1, 2, 3)
    private val frameB = ByteArray(700) { if (it == 0) 0xFF.toByte() else if (it == 1) 0xF9.toByte() else 7 }

    @Test
    fun `первый пакет определяет формат`() {
        assertTrue(matches(file(page(listOf(signaturePacket()), 0))))
        assertFalse(matches(file(page(listOf(ByteArray(60)), 0))))
        assertFalse(matches(file(ByteArray(100))))
    }

    @Test
    fun `кадры переносятся без изменений, метаданные отбрасываются`() {
        val comment = byteArrayOf(0x84.toByte(), 0, 0, 2, 9, 9)
        val source = file(
            page(listOf(signaturePacket()), 0),
            page(listOf(comment), 0),
            page(listOf(frameA, frameB), 4096),
        )
        val target = File.createTempFile("out", ".flac")
        target.deleteOnExit()
        FileAudioInput(source).open().use { OggFlac.unwrap(it, target) }
        val bytes = target.readBytes()
        assertEquals("fLaC", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(0x80, bytes[4].toInt() and 0x80)
        assertArrayEquals(streamInfo, bytes.copyOfRange(8, 42))
        assertArrayEquals(frameA + frameB, bytes.copyOfRange(42, bytes.size))
    }

    @Test
    fun `длительность берётся из метки последней страницы`() {
        val source = file(page(listOf(signaturePacket()), 0), page(listOf(frameA), 32_000))
        assertEquals(2_000_000L, FileAudioInput(source).open().use(OggFlac::durationUs))
    }

    @Test(expected = java.io.IOException::class)
    fun `обрыв внутри страницы это ошибка чтения`() {
        val whole = page(listOf(signaturePacket()), 0) + page(listOf(frameB), 4096)
        val target = File.createTempFile("out", ".flac")
        target.deleteOnExit()
        FileAudioInput(file(whole.copyOf(whole.size - 100))).open().use { OggFlac.unwrap(it, target) }
    }

    private fun matches(file: File): Boolean = FileAudioInput(file).open().use(OggFlac::matches)
}
