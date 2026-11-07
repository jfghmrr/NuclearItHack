package ru.stenokot.app.audio

import android.media.AudioFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import ru.stenokot.core.audio.FileAudioInput

/** Разбор WAV, который не читает MediaExtractor. */
class LargeWavTest {
    private fun header(magic: String, fmt: ByteArray, dataDeclared: Long, ds64Data: Long?): ByteArray {
        val out = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        out.put(magic.toByteArray())
        out.putInt(-1)
        out.put("WAVE".toByteArray())
        if (ds64Data != null) {
            out.put("ds64".toByteArray())
            out.putInt(28)
            out.putLong(0)
            out.putLong(ds64Data)
            out.putLong(0)
            out.putInt(0)
        }
        out.put("fmt ".toByteArray())
        out.putInt(fmt.size)
        out.put(fmt)
        out.put("data".toByteArray())
        out.putInt(dataDeclared.toInt())
        return out.array().copyOf(out.position())
    }

    private fun fmt(tag: Int, channels: Int, rate: Int, bits: Int, extraKind: Int? = null): ByteArray {
        val size = if (extraKind != null) 40 else 16
        val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        val frame = channels * bits / 8
        out.putShort(tag.toShort())
        out.putShort(channels.toShort())
        out.putInt(rate)
        out.putInt(rate * frame)
        out.putShort(frame.toShort())
        out.putShort(bits.toShort())
        if (extraKind != null) {
            out.putShort(22)
            out.putShort(bits.toShort())
            out.putInt(3)
            out.putShort(extraKind.toShort())
        }
        return out.array()
    }

    private fun file(head: ByteArray, length: Long): File {
        val file = File.createTempFile("large", ".wav")
        file.deleteOnExit()
        RandomAccessFile(file, "rw").use { output ->
            output.write(head)
            output.setLength(length)
        }
        return file
    }

    private fun detect(file: File): LargeWav? = FileAudioInput(file).open().use(LargeWav::detect)

    @Test
    fun rf64TakesTheSizeFromItsOwnChunk() {
        val data = 5_000_000_000L
        val head = header("RF64", fmt(1, 2, 44_100, 16), -1, data)
        val wav = detect(file(head, head.size + data))
        assertNotNull(wav)
        assertEquals(head.size.toLong(), wav!!.dataOffset)
        assertEquals(data, wav.dataBytes)
        assertEquals(4, wav.frameBytes)
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, wav.encoding)
        assertEquals(data / 4 * 1000 / 44_100, wav.durationMs)
    }

    @Test
    fun smallRf64IsAlsoReadDirectlyBecauseTheExtractorRefusesIt() {
        val head = header("RF64", fmt(1, 1, 16_000, 16), -1, 1_000)
        assertEquals(1_000L, detect(file(head, head.size + 1_000L))?.dataBytes)
    }

    @Test
    fun ordinaryWavStaysWithTheExtractor() {
        val head = header("RIFF", fmt(1, 2, 44_100, 16), 3_000_000_000, null)
        assertNull(detect(file(head, head.size + 3_000_000_000L)))
    }

    @Test
    fun riffOverFourGigabytesIgnoresItsOverflowedField() {
        val data = 5_000_000_000L
        val head = header("RIFF", fmt(1, 2, 44_100, 16), data and 0xFFFFFFFFL, null)
        assertEquals(data, detect(file(head, head.size + data))?.dataBytes)
    }

    @Test
    fun extensibleFormatTakesTheKindFromSubformat() {
        val head = header("RF64", fmt(0xFFFE, 2, 48_000, 24, extraKind = 1), -1, 6_000)
        val wav = detect(file(head, head.size + 6_000L))
        assertEquals(AudioFormat.ENCODING_PCM_24BIT_PACKED, wav?.encoding)
        assertEquals(6, wav?.frameBytes)
    }

    @Test
    fun floatSamplesAreRecognised() {
        val head = header("RF64", fmt(3, 1, 48_000, 32), -1, 4_000)
        assertEquals(AudioFormat.ENCODING_PCM_FLOAT, detect(file(head, head.size + 4_000L))?.encoding)
    }

    @Test
    fun compressedPayloadIsNotTreatedAsSamples() {
        val head = header("RF64", fmt(0x55, 2, 44_100, 16), -1, 1_000)
        assertNull(detect(file(head, head.size + 1_000L)))
    }

    @Test
    fun otherFilesAreNotWav() {
        assertNull(detect(file("ID3".toByteArray() + ByteArray(100), 1_000)))
    }
}
