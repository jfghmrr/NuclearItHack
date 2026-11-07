package ru.stenokot.app.audio

import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Запись и чтение pcm16k.wav, измерение громкости. */
class WavPcmReaderTest {
    private fun wav(samples: FloatArray): File {
        val file = File.createTempFile("pcm", ".wav")
        file.deleteOnExit()
        PcmWavWriter(file, 16000).use { writer ->
            writer.write(samples, 0, samples.size)
            writer.finish()
        }
        return file
    }

    private fun tone(seconds: Double, amplitude: Float): FloatArray =
        FloatArray((seconds * 16000).toInt()) { amplitude * sin(it * 0.2).toFloat() }

    private fun rmsDb(samples: FloatArray): Double {
        var sum = 0.0
        for (sample in samples) sum += sample * sample
        return 10 * log10(sum / samples.size)
    }

    @Test
    fun `заголовок и число отсчётов совпадают с записанным`() {
        val source = tone(1.0, 0.1f)
        val file = wav(source)
        assertEquals(44L + source.size * 2, file.length())
        WavPcmReader.open(file).use { assertEquals(source.size.toLong(), it.totalSamples) }
    }

    @Test
    fun `тихая запись приводится к цели`() {
        WavPcmReader.open(wav(tone(5.0, 0.01f))).use { reader ->
            val read = reader.read(0, reader.totalSamples.toInt())
            assertEquals(-23.0, rmsDb(read), 0.5)
        }
    }

    @Test
    fun `пауза не поднимает усиление`() {
        val speech = tone(5.0, 0.05f)
        val withPause = speech + FloatArray(16000 * 55)
        val plain = WavPcmReader.open(wav(speech)).use { it.gain }
        val gated = WavPcmReader.open(wav(withPause)).use { it.gain }
        assertEquals(plain, gated, plain * 0.02f)
    }

    @Test
    fun `цифровая тишина не усиливается`() {
        WavPcmReader.open(wav(FloatArray(16000))).use { assertEquals(1f, it.gain) }
    }

    @Test
    fun `усиление ограничено`() {
        WavPcmReader.open(wav(tone(2.0, 0.0005f))).use { reader ->
            assertTrue(reader.gain <= 31.7f)
        }
    }

    @Test
    fun `шкала времени не меняется`() {
        val source = FloatArray(32000)
        source[20000] = 0.5f
        WavPcmReader.open(wav(source)).use { reader ->
            val slice = reader.read(19000, 2000)
            assertEquals(2000, slice.size)
            assertEquals(1000, slice.indices.maxBy { abs(slice[it]) })
        }
    }

    @Test
    fun `чтение за концом записи укорачивает массив`() {
        WavPcmReader.open(wav(tone(1.0, 0.1f))).use { reader ->
            assertEquals(100, reader.read(15900, 1000).size)
            assertEquals(0, reader.read(99999, 10).size)
        }
    }

    @Test
    fun `пределы не трогают речь и не дают перегруза`() {
        assertEquals(0.5f, WavPcmReader.limit(0.5f))
        assertEquals(-0.5f, WavPcmReader.limit(-0.5f))
        assertTrue(WavPcmReader.limit(5f) <= 1f)
        assertTrue(WavPcmReader.limit(-5f) >= -1f)
        assertTrue(sqrt(WavPcmReader.limit(1.2f)) > 0.9f)
    }
}
