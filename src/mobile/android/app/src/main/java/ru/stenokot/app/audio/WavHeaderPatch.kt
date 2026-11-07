package ru.stenokot.app.audio

import android.media.MediaDataSource
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Поправка заголовка WAV от потокового писателя: он не знал длину заранее и оставил в размерах ноль или
 * максимум. MediaExtractor такой файл не открывает, хотя данные целы. Файл не меняется, верные размеры
 * подставляются при чтении. Обычный обрезанный WAV сюда не попадает: у него размер настоящий, и это порча.
 */
internal class WavHeaderPatch private constructor(private val fields: List<Field>) {
    private class Field(val offset: Long, val bytes: ByteArray)

    /** Накладывает поправку на [length] байт [buffer] от [offset], прочитанных с позиции [position]. */
    fun applyTo(position: Long, buffer: ByteArray, offset: Int, length: Int) {
        for (field in fields) {
            val from = max(position, field.offset)
            val to = min(position + length, field.offset + field.bytes.size)
            for (at in from until to) {
                buffer[offset + (at - position).toInt()] = field.bytes[(at - field.offset).toInt()]
            }
        }
    }

    companion object {
        private const val SCAN_LIMIT = 1L shl 20
        private const val CHUNK_HEADER = 8L
        private const val UNSET_LARGE = 0xFFFFFFFFL

        /** Ищет блок data. Значение null: файл не WAV или размер записан честно. */
        fun detect(file: File): WavHeaderPatch? = RandomAccessFile(file, "r").use { input ->
            val head = ByteArray(12)
            if (input.read(head) < head.size) return@use null
            if (String(head, 0, 4, Charsets.ISO_8859_1) != "RIFF" ||
                String(head, 8, 4, Charsets.ISO_8859_1) != "WAVE"
            ) {
                return@use null
            }
            val total = input.length()
            val chunk = ByteArray(CHUNK_HEADER.toInt())
            var position = 12L
            while (position + CHUNK_HEADER <= min(total, SCAN_LIMIT)) {
                input.seek(position)
                input.readFully(chunk)
                val size = littleInt(chunk).toLong() and UNSET_LARGE
                if (String(chunk, 0, 4, Charsets.ISO_8859_1) == "data") {
                    val available = total - position - CHUNK_HEADER
                    if ((size != 0L && size != UNSET_LARGE) || available <= 0) return@use null
                    return@use WavHeaderPatch(
                        listOf(
                            Field(4, encode(total - CHUNK_HEADER)),
                            Field(position + 4, encode(available)),
                        ),
                    )
                }
                position += CHUNK_HEADER + size + (size and 1)
            }
            null
        }

        private fun littleInt(chunk: ByteArray): Int =
            ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int

        private fun encode(value: Long): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()
    }
}

/** Файл WAV с поправленным заголовком для MediaExtractor. Чтение идёт из потока извлекателя. */
internal class PatchedWavSource(file: File, private val patch: WavHeaderPatch) : MediaDataSource() {
    private val input = RandomAccessFile(file, "r")

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        input.seek(position)
        val read = input.read(buffer, offset, size)
        if (read > 0) patch.applyTo(position, buffer, offset, read)
        return read
    }

    override fun getSize(): Long = input.length()

    override fun close() {
        input.close()
    }
}
