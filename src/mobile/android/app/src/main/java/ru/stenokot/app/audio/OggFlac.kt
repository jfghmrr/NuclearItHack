package ru.stenokot.app.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * FLAC в контейнере Ogg. MediaExtractor читает из Ogg только Vorbis и Opus, поэтому поток FLAC вынимается из
 * страниц Ogg в обычный файл .flac. Кадры не меняются, только снимается упаковка.
 */
internal object OggFlac {
    private const val PAGE_HEADER = 27
    private const val SEGMENT_FULL = 255
    private const val SIGNATURE_PACKET_BYTES = 51
    private const val NATIVE_HEADER_OFFSET = 9
    private const val STREAM_INFO_HEADER_OFFSET = 13
    private const val STREAM_INFO_BYTES = 38
    private const val LAST_BLOCK_FLAG = 0x80
    private const val MAX_PACKET_BYTES = 4 * 1024 * 1024
    private const val TAIL_BYTES = 128 * 1024
    private const val BUFFER_BYTES = 1 shl 16
    private const val SAMPLE_RATE_OFFSET = 27
    private val PAGE_MAGIC = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())

    /** Проверяет по первому пакету, что в [file] лежит FLAC. */
    fun matches(file: File): Boolean = RandomAccessFile(file, "r").use { input ->
        val head = ByteArray(PAGE_HEADER + SEGMENT_FULL + SIGNATURE_PACKET_BYTES)
        val got = input.read(head)
        if (got < PAGE_HEADER || !head.startsWithMagic(0)) return@use false
        val first = PAGE_HEADER + (head[PAGE_HEADER - 1].toInt() and 0xFF)
        got >= first + 5 && head[first].toInt() == 0x7F && String(head, first + 1, 4, Charsets.US_ASCII) == "FLAC"
    }

    /** Длительность по метке последней страницы, микросекунды. Значение null: страница не найдена. */
    fun durationUs(file: File): Long? = RandomAccessFile(file, "r").use { input ->
        val sampleRate = readSampleRate(input) ?: return@use null
        val size = input.length()
        val tail = ByteArray(min(size, TAIL_BYTES.toLong()).toInt())
        input.seek(size - tail.size)
        input.readFully(tail)
        val last = (tail.size - PAGE_HEADER downTo 0).firstOrNull { tail.startsWithMagic(it) } ?: return@use null
        val granule = ByteBuffer.wrap(tail, last + 6, 8).order(ByteOrder.LITTLE_ENDIAN).long
        if (granule <= 0) null else granule * 1_000_000 / sampleRate
    }

    /** Перекладывает [source] в [target]. При [limitBytes] пишется только начало потока, этого хватает проверке. */
    fun unwrap(source: File, target: File, limitBytes: Long = Long.MAX_VALUE) {
        DataInputStream(BufferedInputStream(source.inputStream(), BUFFER_BYTES)).use { input ->
            BufferedOutputStream(target.outputStream(), BUFFER_BYTES).use { output ->
                Unwrapper(input, output, limitBytes).run()
            }
        }
    }

    private fun readSampleRate(input: RandomAccessFile): Int? {
        val head = ByteArray(PAGE_HEADER + SEGMENT_FULL + SIGNATURE_PACKET_BYTES)
        input.seek(0)
        val got = input.read(head)
        if (got < PAGE_HEADER) return null
        val first = PAGE_HEADER + (head[PAGE_HEADER - 1].toInt() and 0xFF)
        val info = first + STREAM_INFO_HEADER_OFFSET + 4
        val packed = info + SAMPLE_RATE_OFFSET - 17
        if (got < packed + 3) return null
        val rate = ((head[packed].toInt() and 0xFF) shl 12) or
            ((head[packed + 1].toInt() and 0xFF) shl 4) or
            ((head[packed + 2].toInt() and 0xFF) shr 4)
        return rate.takeIf { it > 0 }
    }

    private fun ByteArray.startsWithMagic(at: Int): Boolean =
        at + PAGE_MAGIC.size <= size && PAGE_MAGIC.indices.all { this[at + it] == PAGE_MAGIC[it] }

    private class Unwrapper(
        private val input: DataInputStream,
        private val output: OutputStream,
        private val limitBytes: Long,
    ) {
        private val header = ByteArray(PAGE_HEADER)
        private val lacing = ByteArray(SEGMENT_FULL)
        private var packet = ByteArray(BUFFER_BYTES)
        private var packetSize = 0
        private var packets = 0
        private var written = 0L
        private var audioStarted = false
        private var serial: Int? = null

        fun run() {
            while (written < limitBytes && readPageHeader()) {
                val segments = header[PAGE_HEADER - 1].toInt() and 0xFF
                input.readFully(lacing, 0, segments)
                val pageSerial = ByteBuffer.wrap(header, 14, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val ours = serial == null || serial == pageSerial
                if (serial == null) serial = pageSerial
                for (index in 0 until segments) {
                    val length = lacing[index].toInt() and 0xFF
                    if (!ours) {
                        input.skipBytes(length)
                        continue
                    }
                    appendSegment(length)
                    if (length < SEGMENT_FULL) completePacket()
                }
            }
            if (packets == 0) throw IOException("В Ogg нет пакетов")
        }

        /** Конец файла на границе страницы это штатный конец. Обрыв внутри страницы станет ошибкой чтения. */
        private fun readPageHeader(): Boolean {
            try {
                input.readFully(header)
            } catch (_: EOFException) {
                return false
            }
            if (header.startsWithMagic(0)) return true
            if (packets == 0) throw IOException("Нарушена структура Ogg")
            return false
        }

        private fun appendSegment(length: Int) {
            if (packetSize + length > packet.size) {
                if (packetSize + length > MAX_PACKET_BYTES) throw IOException("Слишком большой пакет Ogg")
                packet = packet.copyOf(max(packet.size * 2, packetSize + length))
            }
            input.readFully(packet, packetSize, length)
            packetSize += length
        }

        private fun completePacket() {
            if (packets == 0) {
                writeStreamHeader()
            } else if (audioStarted || isAudioFrame()) {
                audioStarted = true
                output.write(packet, 0, packetSize)
                written += packetSize
            }
            packets++
            packetSize = 0
        }

        /** Только STREAMINFO с флагом последнего блока: метаданные распознаванию не нужны. */
        private fun writeStreamHeader() {
            if (packetSize < STREAM_INFO_HEADER_OFFSET + STREAM_INFO_BYTES) throw IOException("Короткий заголовок FLAC")
            packet[STREAM_INFO_HEADER_OFFSET] = (packet[STREAM_INFO_HEADER_OFFSET].toInt() or LAST_BLOCK_FLAG).toByte()
            output.write(packet, NATIVE_HEADER_OFFSET, STREAM_INFO_HEADER_OFFSET + STREAM_INFO_BYTES - NATIVE_HEADER_OFFSET)
        }

        private fun isAudioFrame(): Boolean =
            packetSize >= 2 && packet[0].toInt() and 0xFF == 0xFF && packet[1].toInt() and 0xFC == 0xF8
    }
}
