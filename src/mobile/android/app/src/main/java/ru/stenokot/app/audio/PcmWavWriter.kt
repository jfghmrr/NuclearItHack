package ru.stenokot.app.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Размер заголовка WAV без дополнительных блоков. */
internal const val WAV_HEADER_BYTES = 44

/** Заголовок WAV для PCM16 моно. Размеры больше двух гигабайт записываются как беззнаковые. */
internal fun pcm16MonoHeader(sampleRate: Int, dataBytes: Long): ByteArray {
    val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray(Charsets.US_ASCII))
    header.putInt((dataBytes + WAV_HEADER_BYTES - 8).toInt())
    header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
    header.putInt(16)
    header.putShort(1)
    header.putShort(1)
    header.putInt(sampleRate)
    header.putInt(sampleRate * 2)
    header.putShort(2)
    header.putShort(16)
    header.put("data".toByteArray(Charsets.US_ASCII))
    header.putInt(dataBytes.toInt())
    return header.array()
}

/**
 * Потоковая запись PCM16 моно в WAV. Заголовок дописывается в [finish], поэтому файл без
 * завершения не считается готовым и остаётся под временным именем.
 */
internal class PcmWavWriter(file: File, private val sampleRate: Int) : Closeable {
    private val output = RandomAccessFile(file, "rw")
    private val buffer = ByteBuffer.allocate(BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    /** Число записанных отсчётов. */
    var samples: Long = 0
        private set

    init {
        output.setLength(0)
        output.write(ByteArray(WAV_HEADER_BYTES))
    }

    /** Пишет [count] значений [source] от позиции [offset]. Значения лежат в диапазоне от -1 до 1. */
    fun write(source: FloatArray, offset: Int, count: Int) {
        for (index in offset until offset + count) {
            if (buffer.remaining() < 2) flush()
            val scaled = (source[index] * MAX_SHORT).roundToInt().coerceIn(-MAX_SHORT - 1, MAX_SHORT)
            buffer.putShort(scaled.toShort())
        }
        samples += count
    }

    /** Дописывает хвост и заголовок, затем сбрасывает файл на диск. */
    fun finish() {
        flush()
        val header = pcm16MonoHeader(sampleRate, samples * 2)
        output.seek(0)
        output.write(header)
        output.fd.sync()
    }

    override fun close() {
        output.close()
    }

    private fun flush() {
        output.write(buffer.array(), 0, buffer.position())
        buffer.clear()
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_SHORT = 32767
    }
}
