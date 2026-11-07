package ru.stenokot.app.audio

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.log10
import kotlin.math.sqrt

/** Микрофон нельзя открыть. */
internal class MicUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Открытый микрофон. Методы вызываются из потока записи, кроме [interrupt]. */
interface MicSource : Closeable {
    /** Настоящая частота, Гц. */
    val sampleRate: Int

    /** Блокирующее чтение. Возвращает число отсчётов, отрицательное значение означает сбой. */
    fun read(target: ShortArray): Int

    /** Система отдала микрофон другому приложению, например звонку, и вместо звука идёт тишина. */
    fun silenced(): Boolean

    /** Будит зависшее чтение. Вызывается из другого потока. */
    fun interrupt()
}

/** Почему запись закончилась. */
enum class MicEnd {
    /** Пользователь остановил запись или отменил её. */
    STOPPED,

    /** Закончилось место: остаток нужен, чтобы запись прошла обработку. */
    NO_SPACE,

    /** Микрофон пропал и не вернулся. */
    MIC_LOST,

    /** Диск перестал принимать данные. */
    WRITE_FAILED,
}

/** Итог записи: [samples] отсчётов уже в файле. */
internal data class MicResult(val end: MicEnd, val samples: Long)

/** Снимок записи для интерфейса. [busy] означает, что микрофон сейчас занят или пропал. */
internal data class MicProgress(val elapsedMs: Long, val level: Float, val busy: Boolean)

/** Правила записи, которым не нужен Android. */
internal object MicRules {
    /** Короче этого запись отменяется без вопроса. */
    const val CANCEL_CONFIRM_MS = 3_000L

    private const val MAX_DATA_BYTES = 4_000_000_000L
    private const val RESERVE_BYTES = 64L * 1024 * 1024
    private const val COPIES_FOR_PIPELINE = 2
    private const val FLOOR_DB = -60f
    private const val PCM_SCALE = 32768f
    private val NameFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")

    /** Нужно ли спрашивать перед удалением записи длиной [elapsedMs]. */
    fun needsCancelConfirmation(elapsedMs: Long): Boolean = elapsedMs >= CANCEL_CONFIRM_MS

    /** Имя файла по дате начала записи. Двоеточие запрещено в имени результата, поэтому время через дефис. */
    fun fileName(startedAtMs: Long, zone: ZoneId): String =
        "Запись ${NameFormat.format(Instant.ofEpochMilli(startedAtMs).atZone(zone))}.wav"

    /**
     * Пора остановиться. Обработка берёт копию файла и pcm16k.wav того же размера, поэтому
     * свободного места должно хватать на обе, иначе готовая запись упрётся в нехватку места.
     * Предел размера нужен потому, что поля длины в WAV 32-битные.
     */
    fun outOfRoom(writtenBytes: Long, freeBytes: Long): Boolean =
        writtenBytes >= MAX_DATA_BYTES || freeBytes < writtenBytes * COPIES_FOR_PIPELINE + RESERVE_BYTES

    /** Уровень блока для шкалы от 0 до 1: среднеквадратичное значение на шкале от -60 дБFS до нуля. */
    fun level(samples: ShortArray, count: Int): Float {
        if (count <= 0) return 0f
        var sum = 0.0
        for (index in 0 until count) {
            val value = samples[index] / PCM_SCALE
            sum += value * value
        }
        val rms = sqrt(sum / count)
        if (rms <= 0.0) return 0f
        val db = 20f * log10(rms.toFloat())
        return ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
    }
}

/**
 * Цикл записи. Никаких накоплений в памяти: блок прочитан, блок ушёл в файл. Пока система держит
 * микрофон у звонка, тишина в файл не пишется, а запись сама продолжается, когда он освободится.
 * Если микрофон пропал совсем, уже записанное остаётся целым и идёт в обработку.
 */
internal class MicCapture(
    private val open: (sampleRate: Int?) -> MicSource,
    private val freeBytes: () -> Long,
    private val onProgress: (MicProgress) -> Unit,
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    @Volatile
    private var stopping = false

    @Volatile
    private var mic: MicSource? = null

    /** Просит закончить запись. Безопасно вызывать из любого потока и не один раз. */
    fun stop() {
        stopping = true
        mic?.interrupt()
    }

    /** Пишет в [file], пока не попросят остановиться. Первое открытие бросает [MicUnavailable]. */
    fun run(file: File): MicResult {
        if (MicRules.outOfRoom(0, freeBytes())) return MicResult(MicEnd.NO_SPACE, 0)
        val first = open(null)
        mic = first
        if (stopping) first.interrupt()
        val sampleRate = first.sampleRate
        val writer = try {
            MicWavWriter(file, sampleRate)
        } catch (failure: IOException) {
            first.close()
            mic = null
            return MicResult(MicEnd.WRITE_FAILED, 0)
        }
        onProgress(MicProgress(0, 0f, false))
        val end = record(writer, ShortArray(sampleRate / BLOCKS_PER_SECOND), sampleRate)
        mic?.close()
        mic = null
        return MicResult(end, finish(writer))
    }

    private fun record(writer: MicWavWriter, block: ShortArray, sampleRate: Int): MicEnd {
        var blocks = 0
        var busy = false
        try {
            while (true) {
                val source = mic ?: return MicEnd.MIC_LOST
                val read = source.read(block)
                when {
                    read < 0 -> {
                        if (stopping) return MicEnd.STOPPED
                        onProgress(MicProgress(writer.durationMs, 0f, true))
                        if (!reopen(source, sampleRate)) return if (stopping) MicEnd.STOPPED else MicEnd.MIC_LOST
                        busy = false
                    }
                    read == 0 -> {
                        if (stopping) return MicEnd.STOPPED
                        pause(EMPTY_READ_PAUSE_MS)
                    }
                    else -> {
                        if (blocks % BLOCKS_PER_SECOND == 0) busy = source.silenced()
                        if (!busy) writer.write(block, read)
                        blocks += 1
                        val level = if (busy) 0f else MicRules.level(block, read)
                        onProgress(MicProgress(writer.durationMs, level, busy))
                        if (blocks % SYNC_BLOCKS == 0) writer.sync()
                        if (blocks % BLOCKS_PER_SECOND == 0 && MicRules.outOfRoom(writer.dataBytes, freeBytes())) {
                            return MicEnd.NO_SPACE
                        }
                        if (stopping) return MicEnd.STOPPED
                    }
                }
            }
        } catch (failure: IOException) {
            return MicEnd.WRITE_FAILED
        }
    }

    /** Открывает микрофон заново с прежней частотой: в одном файле частота меняться не может. */
    private fun reopen(old: MicSource, sampleRate: Int): Boolean {
        old.close()
        mic = null
        repeat(REOPEN_ATTEMPTS) {
            if (stopping) return false
            pause(REOPEN_PAUSE_MS)
            val fresh = try {
                open(sampleRate)
            } catch (failure: MicUnavailable) {
                null
            }
            if (fresh != null) {
                mic = fresh
                return true
            }
        }
        return false
    }

    /** Сбой на последнем шаге данные не теряет: заголовок с нулевой длиной читает [WavHeaderPatch]. */
    private fun finish(writer: MicWavWriter): Long {
        try {
            writer.finish()
        } catch (failure: IOException) {
            writer.close()
        }
        return writer.samples
    }

    private companion object {
        const val BLOCKS_PER_SECOND = 10
        const val SYNC_BLOCKS = 5 * BLOCKS_PER_SECOND
        const val EMPTY_READ_PAUSE_MS = 20L
        const val REOPEN_ATTEMPTS = 20
        const val REOPEN_PAUSE_MS = 500L
    }
}
