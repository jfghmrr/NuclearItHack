package ru.stenokot.core.vad

import kotlin.math.ceil
import kotlin.math.roundToInt
import ru.stenokot.core.model.SpeechSegment

/** Параметры нарезки. Длительности в миллисекундах, вероятности речи от 0 до 1. */
data class ChunkPlannerConfig(
    val sampleRate: Int = 16_000,
    val windowSamples: Int = 512,
    val speechProbability: Float = 0.35f,
    val pauseProbability: Float = 0.2f,
    val skipPauseMs: Long = 2_000,
    val minSpeechMs: Long = 100,
    val targetMinMs: Long = 15_000,
    val targetMaxMs: Long = 20_000,
    val hardMaxMs: Long = 22_000,
    val paddingMs: Long = 200,
    val overlapMs: Long = 2_000,
)

/**
 * Режет запись на непрерывные срезы для распознавания. Определитель речи здесь нужен только для
 * разрезов и пропуска длинных пауз: пауза короче двух секунд остаётся в срезе вместе с мычанием.
 * Сплошная речь режется в точке минимальной вероятности на участке цели, а если провала нет,
 * соседние срезы идут внахлёст. Нахлёст виден по границам срезов, его разбирает склейка слов.
 */
class ChunkPlanner(private val config: ChunkPlannerConfig = ChunkPlannerConfig()) {
    private val padding = samples(config.paddingMs)
    private val hardMax = samples(config.hardMaxMs)
    private val targetMin = samples(config.targetMinMs)
    private val targetMax = samples(config.targetMaxMs)
    private val overlap = samples(config.overlapMs)

    /** Строит срезы по вероятностям речи окнами [ChunkPlannerConfig.windowSamples] отсчётов. */
    fun plan(probabilities: FloatArray, totalSamples: Long): List<SpeechSegment> {
        val out = ArrayList<SpeechSegment>()
        for (region in speechRegions(probabilities, totalSamples)) {
            split(region, probabilities, out)
        }
        return out
    }

    /**
     * Строит срезы по готовым участкам речи, когда вероятности недоступны.
     * Участок считается уверенной речью, промежуток между ними паузой.
     */
    fun planFromSpans(spans: List<SpeechSegment>, totalSamples: Long): List<SpeechSegment> {
        val windows = ((totalSamples + config.windowSamples - 1) / config.windowSamples).toInt()
        val probabilities = FloatArray(windows)
        for (span in spans) {
            val first = (span.startSample / config.windowSamples).toInt().coerceAtLeast(0)
            val last = ((span.endSample - 1) / config.windowSamples).toInt().coerceAtMost(windows - 1)
            if (first <= last) probabilities.fill(1f, first, last + 1)
        }
        return plan(probabilities, totalSamples)
    }

    private fun speechRegions(probabilities: FloatArray, totalSamples: Long): List<LongRange> {
        val quiet = quietWindows(probabilities)
        val minPause = ceil(samples(config.skipPauseMs).toDouble() / config.windowSamples).toInt()
        val regions = ArrayList<LongRange>()
        var regionStart = NO_REGION
        var window = 0

        fun closeRegion(endWindow: Int) {
            val hasSpeech = regionStart != NO_REGION && (regionStart until endWindow).any { !quiet[it] }
            if (hasSpeech) regions += padded(regionStart, endWindow, totalSamples)
            regionStart = NO_REGION
        }

        while (window < quiet.size) {
            var runEnd = window
            while (runEnd < quiet.size && quiet[runEnd]) runEnd++
            if (runEnd - window >= minPause) {
                closeRegion(window)
                window = runEnd
            } else {
                if (regionStart == NO_REGION) regionStart = window
                window = maxOf(runEnd, window + 1)
            }
        }
        closeRegion(quiet.size)
        return regions
    }

    /** Окно считается тихим ниже порога паузы; вспышка без уверенной речи пауз не разрывает. */
    private fun quietWindows(probabilities: FloatArray): BooleanArray {
        val quiet = BooleanArray(probabilities.size) { probabilities[it] < config.pauseProbability }
        val minSpeech = (config.minSpeechMs * config.sampleRate / 1000.0 / config.windowSamples)
            .roundToInt()
            .coerceAtLeast(1)
        var from = 0
        while (from < quiet.size) {
            if (quiet[from]) {
                from++
                continue
            }
            var end = from
            var run = 0
            var longest = 0
            while (end < quiet.size && !quiet[end]) {
                run = if (probabilities[end] >= config.speechProbability) run + 1 else 0
                longest = maxOf(longest, run)
                end++
            }
            if (longest < minSpeech) quiet.fill(true, from, end)
            from = end
        }
        return quiet
    }

    private fun padded(startWindow: Int, endWindow: Int, totalSamples: Long): LongRange {
        val start = (startWindow * config.windowSamples.toLong() - padding).coerceAtLeast(0)
        val end = (endWindow * config.windowSamples.toLong() + padding).coerceAtMost(totalSamples)
        return start until end
    }

    private fun split(region: LongRange, probabilities: FloatArray, out: MutableList<SpeechSegment>) {
        val end = region.last + 1
        var start = region.first
        while (end - start > hardMax) {
            val dip = deepestDip(probabilities, start + targetMin, start + targetMax)
            if (dip != null) {
                out += SpeechSegment(out.size, start, dip)
                start = dip
            } else {
                out += SpeechSegment(out.size, start, start + targetMax)
                start += targetMax - overlap
            }
        }
        out += SpeechSegment(out.size, start, end)
    }

    /**
     * Ищет точку минимальной вероятности на участке. Значение сглажено по трём окнам, чтобы разрез
     * не попал в смычку внутри слова. Если речь уверенная на всём участке, разрезать негде.
     */
    private fun deepestDip(probabilities: FloatArray, fromSample: Long, toSample: Long): Long? {
        val first = (fromSample / config.windowSamples).toInt()
        val last = minOf((toSample / config.windowSamples).toInt(), probabilities.size) - 1
        var lowest = Float.MAX_VALUE
        var lowestWindow = -1
        for (window in first..last) {
            val value = smoothed(probabilities, window)
            if (value < lowest) {
                lowest = value
                lowestWindow = window
            }
        }
        if (lowestWindow < 0 || lowest >= config.speechProbability) return null
        var plateauEnd = lowestWindow
        while (plateauEnd + 1 <= last && smoothed(probabilities, plateauEnd + 1) <= lowest + PLATEAU_EPSILON) {
            plateauEnd++
        }
        val middle = (lowestWindow + plateauEnd) / 2
        return middle * config.windowSamples.toLong() + config.windowSamples / 2
    }

    private fun smoothed(probabilities: FloatArray, window: Int): Float {
        val before = probabilities[maxOf(window - 1, 0)]
        val after = probabilities[minOf(window + 1, probabilities.size - 1)]
        return (before + probabilities[window] + after) / 3f
    }

    private fun samples(ms: Long): Long = ms * config.sampleRate / 1000

    private companion object {
        const val PLATEAU_EPSILON = 0.001f
        const val NO_REGION = -1
    }
}
