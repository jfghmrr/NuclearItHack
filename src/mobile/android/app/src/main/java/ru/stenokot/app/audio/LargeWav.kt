package ru.stenokot.app.audio

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import ru.stenokot.core.audio.SeekableInput

/**
 * Несжатый WAV, который MediaExtractor не прочитает: RF64 он не открывает вовсе, а поле размера в
 * обычном заголовке кончается на 4 ГБ, и 8 часов стерео в него уже не помещаются. Кодек здесь не нужен,
 * отсчёты лежат в файле подряд, поэтому такие файлы читаются напрямую. Обычный WAV сюда не попадает.
 */
internal class LargeWav private constructor(
    val sampleRate: Int,
    val channels: Int,
    val encoding: Int,
    val dataOffset: Long,
    val dataBytes: Long,
) {
    /** Байт на кадр. */
    val frameBytes: Int = channels * bytesPerSample(encoding)

    /** Длительность по размеру данных, мс. */
    val durationMs: Long = dataBytes / frameBytes * 1000 / sampleRate

    /** Поля блока fmt. */
    private class Format(val sampleRate: Int, val channels: Int, val encoding: Int)

    companion object {
        private const val SCAN_LIMIT = 1L shl 20
        private const val CHUNK_HEADER = 8L
        private const val RIFF_LIMIT = 0xFFFFFFFFL
        private const val DS64_DATA_OFFSET = 8
        private const val MIN_DS64_BYTES = 16
        private const val MIN_FMT_BYTES = 16
        private const val EXTENSIBLE_FMT_BYTES = 26
        private const val TAG_PCM = 1
        private const val TAG_FLOAT = 3
        private const val TAG_EXTENSIBLE = 0xFFFE

        /** Значение null: это не WAV или обычный WAV, с которым справится MediaExtractor. */
        fun detect(input: SeekableInput): LargeWav? {
            val head = ByteArray(12)
            input.seek(0)
            if (input.read(head) < head.size) return null
            val magic = String(head, 0, 4, Charsets.ISO_8859_1)
            if (String(head, 8, 4, Charsets.ISO_8859_1) != "WAVE" || (magic != "RF64" && magic != "RIFF")) return null
            val total = input.length()
            if (magic == "RIFF" && total <= RIFF_LIMIT) return null
            return scan(input, total, magic == "RF64")
        }

        private fun scan(input: SeekableInput, total: Long, rf64: Boolean): LargeWav? {
            var format: Format? = null
            var large: Long? = null
            val chunk = ByteArray(CHUNK_HEADER.toInt())
            var position = 12L
            while (position + CHUNK_HEADER <= min(total, SCAN_LIMIT)) {
                input.seek(position)
                input.readFully(chunk)
                val name = String(chunk, 0, 4, Charsets.ISO_8859_1)
                val size = little(chunk).getInt(4).toLong() and RIFF_LIMIT
                when (name) {
                    "ds64" -> large = readDs64(input, size)
                    "fmt " -> format = readFormat(input, size)
                    "data" -> {
                        val parsed = format ?: return null
                        val start = position + CHUNK_HEADER
                        return LargeWav(
                            parsed.sampleRate,
                            parsed.channels,
                            parsed.encoding,
                            start,
                            dataBytes(size, large.takeIf { rf64 }, total - start),
                        )
                    }
                }
                position += CHUNK_HEADER + size + (size and 1)
            }
            return null
        }

        /** Размер из ds64 точнее всего. Заголовок RIFF на таком файле врёт: поле переполнено или пусто. */
        private fun dataBytes(declared: Long, large: Long?, available: Long): Long = when {
            large != null && large in 1..available -> large
            declared in 1 until RIFF_LIMIT && available <= RIFF_LIMIT -> min(declared, available)
            else -> available
        }

        private fun readDs64(input: SeekableInput, size: Long): Long? {
            if (size < MIN_DS64_BYTES) return null
            val body = ByteArray(MIN_DS64_BYTES)
            input.readFully(body)
            return little(body).getLong(DS64_DATA_OFFSET)
        }

        private fun readFormat(input: SeekableInput, size: Long): Format? {
            if (size < MIN_FMT_BYTES) return null
            val body = ByteArray(min(size, EXTENSIBLE_FMT_BYTES.toLong()).toInt())
            input.readFully(body)
            val fields = little(body)
            val tag = fields.getShort(0).toInt() and 0xFFFF
            val channels = fields.getShort(2).toInt()
            val rate = fields.getInt(4)
            val blockAlign = fields.getShort(12).toInt()
            val bits = fields.getShort(14).toInt()
            val kind = if (tag == TAG_EXTENSIBLE && body.size >= EXTENSIBLE_FMT_BYTES) fields.getShort(24).toInt() else tag
            val encoding = encodingOf(kind, bits) ?: return null
            if (channels <= 0 || rate <= 0 || blockAlign != channels * bytesPerSample(encoding)) return null
            return Format(rate, channels, encoding)
        }

        private fun encodingOf(kind: Int, bits: Int): Int? = when {
            kind == TAG_FLOAT && bits == 32 -> AudioFormat.ENCODING_PCM_FLOAT
            kind != TAG_PCM -> null
            bits == 8 -> AudioFormat.ENCODING_PCM_8BIT
            bits == 16 -> AudioFormat.ENCODING_PCM_16BIT
            bits == 24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            bits == 32 -> AudioFormat.ENCODING_PCM_32BIT
            else -> null
        }

        private fun little(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }
}

/** Размер отсчёта в байтах для кодировки PCM. */
internal fun bytesPerSample(encoding: Int): Int = when (encoding) {
    AudioFormat.ENCODING_PCM_8BIT -> 1
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
    AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
    else -> 2
}
