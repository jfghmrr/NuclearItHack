package ru.stenokot.app.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import ru.stenokot.core.audio.FileAudioInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Поправка размеров в заголовке WAV. */
class WavHeaderPatchTest {
    /** Файл на 5 ГБ удаляется после теста, а не при выходе из JVM. */
    @get:Rule
    val temp = TemporaryFolder()

    private fun wav(declaredData: Long, dataBytes: Int): File {
        val head = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        head.put("RIFF".toByteArray())
        head.putInt(0)
        head.put("WAVEfmt ".toByteArray())
        head.putInt(16)
        head.putShort(1)
        head.putShort(1)
        head.putInt(16000)
        head.putInt(32000)
        head.putShort(2)
        head.putShort(16)
        head.put("data".toByteArray())
        head.putInt(declaredData.toInt())
        val file = File.createTempFile("patch", ".wav", temp.root)
        file.writeBytes(head.array() + ByteArray(dataBytes))
        return file
    }

    private fun patched(file: File): ByteBuffer {
        val bytes = file.readBytes().copyOf(44)
        detect(file)!!.applyTo(0, bytes, 0, bytes.size)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    @Test
    fun `нулевой размер данных заменяется настоящим`() {
        val header = patched(wav(0, 1000))
        assertEquals(1000, header.getInt(40))
        assertEquals(1036, header.getInt(4))
    }

    @Test
    fun `максимальный размер данных заменяется настоящим`() {
        assertEquals(1000, patched(wav(0xFFFFFFFFL, 1000)).getInt(40))
    }

    @Test
    fun `честный размер не трогается`() {
        assertNull(detect(wav(1000, 1000)))
    }

    @Test
    fun `обрезанный файл остаётся обрезанным`() {
        assertNull(detect(wav(5000, 1000)))
    }

    @Test
    fun `поправка попадает в нужное место при чтении с середины`() {
        val file = wav(0, 1000)
        val patch = detect(file)
        assertNotNull(patch)
        val part = file.readBytes().copyOfRange(40, 44)
        patch!!.applyTo(40, part, 0, part.size)
        assertEquals(1000, ByteBuffer.wrap(part).order(ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test
    fun `посторонний файл не считается WAV`() {
        val file = File.createTempFile("patch", ".bin", temp.root)
        file.writeBytes(ByteArray(100))
        assertNull(detect(file))
    }

    @Test
    fun sizeAboveFourGigabytesStopsAtTheFieldLimitInsteadOfWrapping() {
        val file = wav(0xFFFFFFFFL, 16)
        RandomAccessFile(file, "rw").use { it.setLength(5_000_000_000L) }
        val bytes = ByteArray(44)
        detect(file)!!.applyTo(0, bytes, 0, bytes.size)
        val head = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xFFFFFFFFL, head.getInt(4).toLong() and 0xFFFFFFFFL)
        assertEquals(0xFFFFFFFFL, head.getInt(40).toLong() and 0xFFFFFFFFL)
    }

    private fun detect(file: File): WavHeaderPatch? = FileAudioInput(file).open().use(WavHeaderPatch::detect)
}
