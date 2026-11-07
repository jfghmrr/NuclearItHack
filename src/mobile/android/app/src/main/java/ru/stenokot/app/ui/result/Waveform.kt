package ru.stenokot.app.ui.result

import java.io.File
import kotlin.math.min
import kotlin.math.sqrt
import ru.stenokot.app.audio.WavPcmReader

private const val ENVELOPE_BUCKETS = 720
private const val PROBE_SAMPLES = 4_096
private const val FULL_LEVEL = 255
private const val SCALE_PERCENTILE = 0.98f
private const val BYTE_MASK = 0xFF
private const val CACHE_NAME = "waveform.bin"

/**
 * Огибающая записи: один уровень на столбец, от 0 до 255. Из каждого столбца читается не больше
 * четверти секунды, поэтому трёхчасовой файл стоит столько же, сколько короткий. Шкала задана уровнем
 * почти самых громких столбцов, а не максимумом: иначе один громкий смех сплющил бы весь разговор.
 */
fun computeEnvelope(totalSamples: Long, read: (start: Long, count: Int) -> FloatArray): ByteArray {
    val buckets = min(ENVELOPE_BUCKETS.toLong(), totalSamples).toInt()
    if (buckets <= 0) return ByteArray(0)
    val levels = FloatArray(buckets)
    for (bucket in 0 until buckets) {
        val start = totalSamples * bucket / buckets
        val end = totalSamples * (bucket + 1) / buckets
        val samples = read(start, min(end - start, PROBE_SAMPLES.toLong()).toInt())
        var energy = 0f
        for (sample in samples) energy += sample * sample
        levels[bucket] = if (samples.isEmpty()) 0f else sqrt(energy / samples.size)
    }
    val reference = levels.sortedArray()[((buckets - 1) * SCALE_PERCENTILE).toInt()]
    if (reference <= 0f) return ByteArray(buckets)
    return ByteArray(buckets) { ((levels[it] / reference).coerceAtMost(1f) * FULL_LEVEL).toInt().toByte() }
}

/** Сводит огибающую к [bars] столбцам: столбец берёт средний уровень своего отрезка. */
fun envelopeBars(envelope: ByteArray, bars: Int): FloatArray {
    if (envelope.isEmpty() || bars <= 0) return FloatArray(0)
    return FloatArray(bars) { bar ->
        val from = bar * envelope.size / bars
        val to = ((bar + 1) * envelope.size / bars).coerceAtLeast(from + 1).coerceAtMost(envelope.size)
        var sum = 0
        for (index in from until to) sum += envelope[index].toInt() and BYTE_MASK
        sum / (to - from) / FULL_LEVEL.toFloat()
    }
}

/**
 * Огибающая файла [pcm]. Считается один раз и лежит рядом с ним, в каталоге задачи. Сбой чтения
 * означает только, что плеер останется без огибающей: стенограмма от неё не зависит. Вызывать вне
 * основного потока.
 */
fun loadEnvelope(pcm: File): ByteArray? {
    val cache = File(pcm.parentFile, CACHE_NAME)
    runCatching { cache.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
    val envelope = runCatching {
        WavPcmReader.openUnscaled(pcm).use { reader -> computeEnvelope(reader.totalSamples, reader::read) }
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
    runCatching {
        val draft = File(pcm.parentFile, "$CACHE_NAME.tmp")
        draft.writeBytes(envelope)
        draft.renameTo(cache)
    }
    return envelope
}
