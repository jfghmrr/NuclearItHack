package ru.stenokot.app.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Запись с микрофона на диск с постоянной памятью. Заголовок с частотой пишется сразу, а размеры
 * уточняются в [finish]. Файл, брошенный на полпути, восстанавливает [repairMicWav].
 */
internal class MicWavWriter(file: File, private val sampleRate: Int) : Closeable {
    private val output = RandomAccessFile(file, "rw")
    private val block = ByteBuffer.allocate(BLOCK_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    /** Число записанных отсчётов. */
    var samples: Long = 0
        private set

    /** Размер данных без заголовка. */
    val dataBytes: Long get() = samples * 2

    /** Длительность записанного, мс. */
    val durationMs: Long get() = samples * MS_IN_SECOND / sampleRate

    init {
        output.setLength(0)
        output.write(pcm16MonoHeader(sampleRate, 0))
    }

    /** Пишет первые [count] отсчётов [source]. Промежуточного накопления нет: данные сразу у системы. */
    fun write(source: ShortArray, count: Int) {
        var done = 0
        while (done < count) {
            val part = min(count - done, BLOCK_BYTES / 2)
            block.clear()
            block.asShortBuffer().put(source, done, part)
            output.write(block.array(), 0, part * 2)
            done += part
        }
        samples += count
    }

    /** Сбрасывает данные на диск: после сбоя питания потеряется только хвост с последнего вызова. */
    fun sync() {
        output.fd.sync()
    }

    /**
     * Ставит настоящие размеры и закрывает файл. Размер берётся с диска: при переполнении диска
     * часть блока могла записаться, и эти отсчёты тоже сохраняются.
     */
    fun finish() {
        val data = (output.length() - WAV_HEADER_BYTES).coerceAtLeast(0) and EVEN_MASK
        output.setLength(WAV_HEADER_BYTES + data)
        output.seek(0)
        output.write(pcm16MonoHeader(sampleRate, data))
        output.fd.sync()
        output.close()
        samples = data / 2
    }

    override fun close() {
        output.close()
    }

    private companion object {
        const val BLOCK_BYTES = 8 * 1024
        const val MS_IN_SECOND = 1000L
    }
}

private const val EVEN_MASK = -2L
private const val SAMPLE_RATE_OFFSET = 24L

/**
 * Восстанавливает запись, оборвавшуюся вместе с процессом: частота читается из заголовка, размеры
 * считаются по файлу. Возвращает число отсчётов, ноль означает, что сохранять нечего.
 */
internal fun repairMicWav(file: File): Long = RandomAccessFile(file, "rw").use { input ->
    if (input.length() <= WAV_HEADER_BYTES) return@use 0L
    val data = (input.length() - WAV_HEADER_BYTES) and EVEN_MASK
    input.seek(SAMPLE_RATE_OFFSET)
    val rate = ByteArray(Int.SIZE_BYTES)
    input.readFully(rate)
    val sampleRate = ByteBuffer.wrap(rate).order(ByteOrder.LITTLE_ENDIAN).int
    input.setLength(WAV_HEADER_BYTES + data)
    input.seek(0)
    input.write(pcm16MonoHeader(sampleRate, data))
    input.fd.sync()
    data / 2
}
