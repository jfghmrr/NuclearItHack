package ru.stenokot.app.debug

import ru.stenokot.app.audio.MicSource
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val HEADER_BYTES = 44L
private const val SAMPLE_RATE_OFFSET = 24L
private const val MS_IN_SECOND = 1000L

/**
 * Файл WAV (PCM16 моно, заголовок 44 байта) вместо микрофона, с темпом настоящего времени. Нужен на
 * эмуляторе без звука: так цепочка от цикла записи до стенограммы проверяется на настоящей речи.
 * Файл закольцован, поэтому длительность записи задаёт проверяющий.
 */
internal class WavFileMic(path: String) : MicSource {
    private val input = RandomAccessFile(File(path), "r")

    @Volatile
    private var interrupted = false

    override val sampleRate: Int = run {
        input.seek(SAMPLE_RATE_OFFSET)
        Integer.reverseBytes(input.readInt())
    }

    init {
        input.seek(HEADER_BYTES)
    }

    override fun read(target: ShortArray): Int {
        if (interrupted) return 0
        Thread.sleep(target.size * MS_IN_SECOND / sampleRate)
        val bytes = ByteArray(target.size * 2)
        var got = input.read(bytes)
        if (got <= 0) {
            input.seek(HEADER_BYTES)
            got = input.read(bytes)
        }
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(target, 0, got / 2)
        return got / 2
    }

    override fun silenced(): Boolean = false

    override fun interrupt() {
        interrupted = true
    }

    override fun close() {
        input.close()
    }
}
