package ru.stenokot.app.audio

import java.nio.ByteBuffer
import ru.stenokot.core.audio.SeekableInput

/**
 * Служебный кадр Xing, Info или VBRI в начале MP3. Только с ним длительность из заголовка надёжна:
 * иначе платформа оценивает её по битрейту первого кадра.
 */
internal data class Mp3Tag(
    val audioStart: Long,
    val frames: Long?,
    val bytes: Long?,
    val samplesPerFrame: Int,
    val sampleRate: Int,
) {
    /** Длительность из заголовка, миллисекунды. */
    val durationMs: Long?
        get() = frames?.let { it * samplesPerFrame * 1000L / sampleRate }

    /** Заголовок обещает больше данных, чем есть в файле размером [fileSize]. */
    fun isTruncated(fileSize: Long): Boolean {
        val declared = bytes ?: return false
        return fileSize < audioStart + (declared * TRUNCATION_RATIO).toLong()
    }

    companion object {
        private const val TRUNCATION_RATIO = 0.97
        private const val SCAN_BYTES = 64 * 1024
        private const val ID3_HEADER = 10
        private const val FRAME_PROBE = 200
        private const val VBRI_OFFSET = 36
        private val RATES_V1 = intArrayOf(44100, 48000, 32000)

        /** Читает служебный кадр из [input]. Значение null: кадра нет или это не MP3. */
        fun read(input: SeekableInput): Mp3Tag? {
            val start = skipId3(input)
            val window = ByteArray(SCAN_BYTES)
            input.seek(start)
            val got = input.read(window)
            if (got < FRAME_PROBE) return null
            val sync = findFrame(window, got) ?: return null
            return parseFrame(window, sync, got, start + sync)
        }

        private fun skipId3(input: SeekableInput): Long {
            val head = ByteArray(ID3_HEADER)
            input.seek(0)
            if (input.read(head) < ID3_HEADER) return 0
            if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return 0
            var size = 0L
            for (index in 6..9) size = (size shl 7) or (head[index].toLong() and 0x7F)
            val footer = if (head[5].toInt() and 0x10 != 0) ID3_HEADER else 0
            return ID3_HEADER + size + footer
        }

        private fun findFrame(data: ByteArray, size: Int): Int? {
            for (index in 0 until size - FRAME_PROBE) {
                if (data[index].toInt() and 0xFF != 0xFF) continue
                val second = data[index + 1].toInt() and 0xFF
                if (second and 0xE0 != 0xE0) continue
                if ((second shr 3) and 3 == 1 || (second shr 1) and 3 != 1) continue
                val third = data[index + 2].toInt() and 0xFF
                if (third shr 4 == 15 || third shr 4 == 0 || (third shr 2) and 3 == 3) continue
                return index
            }
            return null
        }

        private fun parseFrame(data: ByteArray, at: Int, size: Int, absolute: Long): Mp3Tag? {
            val version = ((data[at + 1].toInt() and 0xFF) shr 3) and 3
            val rateIndex = ((data[at + 2].toInt() and 0xFF) shr 2) and 3
            val mono = ((data[at + 3].toInt() and 0xFF) shr 6) == 3
            val mpeg1 = version == 3
            val rate = RATES_V1[rateIndex] / when (version) {
                3 -> 1
                2 -> 2
                else -> 4
            }
            val perFrame = if (mpeg1) 1152 else 576
            val sideInfo = when {
                mpeg1 && !mono -> 36
                mpeg1 -> 21
                !mono -> 21
                else -> 13
            }
            val buffer = ByteBuffer.wrap(data, 0, size)
            val marker = String(data, at + sideInfo, 4, Charsets.US_ASCII)
            if (marker == "Xing" || marker == "Info") {
                val flags = buffer.getInt(at + sideInfo + 4)
                var field = at + sideInfo + 8
                var frames: Long? = null
                var bytes: Long? = null
                if (flags and 1 != 0) {
                    frames = buffer.getInt(field).toLong() and 0xFFFFFFFFL
                    field += 4
                }
                if (flags and 2 != 0) bytes = buffer.getInt(field).toLong() and 0xFFFFFFFFL
                return Mp3Tag(absolute, frames, bytes, perFrame, rate)
            }
            if (String(data, at + VBRI_OFFSET, 4, Charsets.US_ASCII) == "VBRI") {
                val bytes = buffer.getInt(at + VBRI_OFFSET + 10).toLong() and 0xFFFFFFFFL
                val frames = buffer.getInt(at + VBRI_OFFSET + 14).toLong() and 0xFFFFFFFFL
                return Mp3Tag(absolute, frames, bytes, perFrame, rate)
            }
            return null
        }
    }
}
