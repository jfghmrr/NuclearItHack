package ru.stenokot.app.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh
import ru.stenokot.core.engine.PcmReader

/**
 * Чтение pcm16k.wav блоками с постоянной памятью.
 *
 * Громкость приводится к [DEFAULT_TARGET_DBFS] только в момент чтения: файл не меняется, шкала
 * времени остаётся индексом отсчёта. Ниже примерно -50 дБFS признаки GigaAM упираются в пол логарифма.
 */
class WavPcmReader private constructor(
    private val file: RandomAccessFile,
    private val dataOffset: Long,
    override val totalSamples: Long,
    /** Постоянное усиление записи. Единица означает, что сигнал не менялся. */
    val gain: Float,
) : PcmReader {
    private val channel: FileChannel = file.channel
    private val buffer = ByteBuffer.allocateDirect(BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    /** Возвращает до [count] отсчётов от [start]. За концом записи данных нет, массив будет короче. */
    @Synchronized
    override fun read(start: Long, count: Int): FloatArray {
        require(start >= 0 && count >= 0) { "start=$start count=$count" }
        val from = min(start, totalSamples)
        val length = min(count.toLong(), totalSamples - from).toInt()
        val result = FloatArray(length)
        var done = 0
        while (done < length) {
            val part = min(length - done, buffer.capacity() / 2)
            fillBuffer(dataOffset + (from + done) * 2, part * 2)
            val shorts = buffer.asShortBuffer()
            for (index in 0 until part) {
                result[done + index] = limit(shorts.get(index) / PCM_SCALE * gain)
            }
            done += part
        }
        return result
    }

    override fun close() {
        file.close()
    }

    private fun fillBuffer(position: Long, bytes: Int) {
        buffer.clear().limit(bytes)
        var offset = position
        while (buffer.hasRemaining()) {
            val got = channel.read(buffer, offset)
            if (got < 0) throw IOException("pcm16k.wav короче заголовка")
            offset += got
        }
        buffer.flip()
    }

    companion object {
        /** Целевая громкость записи. */
        const val DEFAULT_TARGET_DBFS = -23f

        private const val BUFFER_BYTES = 1 shl 20
        private const val PCM_SCALE = 32768f
        private const val SAMPLE_RATE = 16000
        private const val BLOCK_SAMPLES = SAMPLE_RATE / 10
        private const val FLOOR_DB = -100
        private const val ABSOLUTE_GATE_DB = -70f
        private const val RELATIVE_GATE_DB = -15f
        private const val MAX_GAIN_DB = 30f
        private const val KNEE = 0.9f
        private const val KNEE_RANGE = 1f - KNEE

        /**
         * Открывает [wav] и одним проходом измеряет громкость. Память постоянная: уровни блоков
         * копятся в гистограмме по децибелу.
         */
        fun open(wav: File, targetDbfs: Float = DEFAULT_TARGET_DBFS): WavPcmReader {
            val file = RandomAccessFile(wav, "r")
            try {
                val layout = readLayout(file)
                val raw = WavPcmReader(file, layout.dataOffset, layout.samples, 1f)
                return WavPcmReader(file, layout.dataOffset, layout.samples, measureGain(raw, targetDbfs))
            } catch (error: Exception) {
                file.close()
                throw error
            }
        }

        /** Плавный предел: редкие щелчки после усиления не дают перегруза, речь не затрагивается. */
        internal fun limit(sample: Float): Float {
            val level = abs(sample)
            if (level <= KNEE) return sample
            val shaped = KNEE + KNEE_RANGE * tanh((level - KNEE) / KNEE_RANGE)
            return if (sample < 0) -shaped else shaped
        }

        /**
         * Считает усиление по блокам 100 мс с отсечкой тишины: паузы не должны поднимать речь выше цели.
         * Запись без блоков выше абсолютного порога не усиливается.
         */
        internal fun measureGain(reader: PcmReader, targetDbfs: Float): Float {
            val bins = -FLOOR_DB + 1
            val energy = DoubleArray(bins)
            val blocks = LongArray(bins)
            var position = 0L
            while (position < reader.totalSamples) {
                val block = reader.read(position, BLOCK_SAMPLES * 64)
                if (block.isEmpty()) break
                var index = 0
                while (index < block.size) {
                    val end = min(index + BLOCK_SAMPLES, block.size)
                    var sum = 0.0
                    for (sample in index until end) sum += block[sample] * block[sample]
                    val mean = sum / (end - index)
                    val bin = (toDb(mean).toInt() - FLOOR_DB).coerceIn(0, bins - 1)
                    energy[bin] += mean
                    blocks[bin]++
                    index = end
                }
                position += block.size
            }
            val absoluteBin = (ABSOLUTE_GATE_DB.toInt() - FLOOR_DB).coerceIn(0, bins - 1)
            val loud = gatedMean(energy, blocks, absoluteBin) ?: return 1f
            val relativeBin = ((toDb(loud) + RELATIVE_GATE_DB).toInt() - FLOOR_DB).coerceIn(0, bins - 1)
            val speech = gatedMean(energy, blocks, max(absoluteBin, relativeBin)) ?: return 1f
            val currentDb = 20f * log10(sqrt(speech)).toFloat()
            val gainDb = min(targetDbfs - currentDb, MAX_GAIN_DB)
            return 10f.pow(gainDb / 20f)
        }

        private fun gatedMean(energy: DoubleArray, blocks: LongArray, fromBin: Int): Double? {
            var sum = 0.0
            var count = 0L
            for (bin in fromBin until energy.size) {
                sum += energy[bin]
                count += blocks[bin]
            }
            return if (count == 0L) null else sum / count
        }

        private fun toDb(meanSquare: Double): Float =
            if (meanSquare <= 0.0) FLOOR_DB.toFloat() else (10.0 * log10(meanSquare)).toFloat()

        private fun readLayout(file: RandomAccessFile): Layout {
            val header = ByteArray(12)
            file.seek(0)
            file.readFully(header)
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(header, 8, 4, Charsets.US_ASCII) != "WAVE"
            ) {
                throw IOException("Это не WAV")
            }
            var position = 12L
            var format: ByteBuffer? = null
            val chunk = ByteArray(8)
            while (position + 8 <= file.length()) {
                file.seek(position)
                file.readFully(chunk)
                val name = String(chunk, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunk, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                when (name) {
                    "fmt " -> {
                        val body = ByteArray(min(size, 40L).toInt())
                        file.readFully(body)
                        format = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                    }
                    "data" -> {
                        val parsed = format ?: throw IOException("Нет блока fmt до data")
                        val channels = parsed.getShort(2).toInt()
                        val rate = parsed.getInt(4)
                        val bits = parsed.getShort(14).toInt()
                        if (parsed.getShort(0).toInt() != 1 || channels != 1 || rate != SAMPLE_RATE || bits != 16) {
                            throw IOException("Ожидался PCM16 моно 16 кГц: каналов $channels, $rate Гц, $bits бит")
                        }
                        val available = file.length() - position - 8
                        return Layout(position + 8, min(size, available) / 2)
                    }
                }
                position += 8 + size + (size and 1)
            }
            throw IOException("В WAV нет блока data")
        }

        private class Layout(val dataOffset: Long, val samples: Long)
    }
}
