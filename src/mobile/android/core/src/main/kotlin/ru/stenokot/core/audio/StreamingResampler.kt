package ru.stenokot.core.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Приёмник готовых отсчётов. Массив используется повторно, данные надо забрать до возврата. */
fun interface SampleSink {
    /** Получает [count] отсчётов из начала [samples]. */
    fun accept(samples: FloatArray, count: Int)
}

/**
 * Сводит поток к моно и передискретизирует его фильтром с окном Кайзера в фиксированной памяти.
 * Положение выхода считается в целых числах, поэтому шкала времени не накапливает ошибку: отсчёт
 * выхода k всегда соответствует моменту k / outputRate, задержка фильтра скомпенсирована.
 * Поток одноразовый: после [flush] экземпляр не используется. Выход ограничен диапазоном от -1 до 1.
 */
class StreamingResampler(
    inputRate: Int,
    private val channels: Int,
    outputRate: Int = DEFAULT_OUTPUT_RATE,
) {
    private val passthrough = inputRate == outputRate
    private val stepWhole: Int
    private val stepFraction: Int
    private val denominator: Int
    private val phases: Int
    private val halfTaps: Int
    private val taps: Int
    private val table: FloatArray

    private val history: FloatArray
    private var historyLength: Int
    private var historyBase: Long

    private var inputFrames = 0L
    private var position = 0L
    private var fraction = 0
    private val output = FloatArray(OUTPUT_BLOCK)
    private var outputLength = 0
    private var flushed = false

    init {
        require(inputRate > 0 && outputRate > 0) { "неверная частота" }
        require(channels > 0) { "неверное число каналов" }
        val divisor = gcd(inputRate, outputRate)
        val numerator = inputRate / divisor
        denominator = outputRate / divisor
        stepWhole = numerator / denominator
        stepFraction = numerator % denominator
        phases = min(denominator, MAX_PHASES)
        val ratio = min(1.0, outputRate.toDouble() / inputRate)
        val cutoff = ratio * if (inputRate > outputRate) DOWN_ROLLOFF else UP_ROLLOFF
        halfTaps = if (passthrough) 0 else ceil(ZERO_CROSSINGS / ratio).toInt()
        taps = 2 * halfTaps
        table = if (passthrough) FloatArray(0) else buildTable(phases, halfTaps, cutoff)
        history = FloatArray(maxOf(MIN_HISTORY, 4 * taps))
        historyLength = halfTaps
        historyBase = -halfTaps.toLong()
    }

    /** Принимает чередующиеся по каналам отсчёты с плавающей точкой. */
    fun process(input: FloatArray, offset: Int, length: Int, sink: SampleSink) {
        require(length % channels == 0) { "неполный кадр" }
        check(!flushed) { "поток уже завершён" }
        var read = offset
        val end = offset + length
        val scale = 1f / channels
        while (read < end) {
            val frames = reserve((end - read) / channels)
            var write = historyLength
            repeat(frames) {
                var sum = 0f
                for (channel in 0 until channels) sum += input[read + channel]
                history[write++] = sum * scale
                read += channels
            }
            commit(frames, sink)
        }
    }

    /** Принимает чередующиеся по каналам 16-битные отсчёты. */
    fun process(input: ShortArray, offset: Int, length: Int, sink: SampleSink) {
        require(length % channels == 0) { "неполный кадр" }
        check(!flushed) { "поток уже завершён" }
        var read = offset
        val end = offset + length
        val scale = 1f / (channels * SHORT_SCALE)
        while (read < end) {
            val frames = reserve((end - read) / channels)
            var write = historyLength
            repeat(frames) {
                var sum = 0
                for (channel in 0 until channels) sum += input[read + channel]
                history[write++] = sum * scale
                read += channels
            }
            commit(frames, sink)
        }
    }

    /** Завершает поток и отдаёт хвост. Хвост фильтра дополняется нулями. */
    fun flush(sink: SampleSink) {
        check(!flushed) { "поток уже завершён" }
        flushed = true
        while (!passthrough && position < inputFrames) {
            val frames = reserve(taps)
            history.fill(0f, historyLength, historyLength + frames)
            historyLength += frames
            produce(sink)
        }
        drain(sink)
    }

    private fun reserve(wanted: Int): Int {
        if (historyLength == history.size) compact()
        return min(wanted, history.size - historyLength)
    }

    private fun commit(frames: Int, sink: SampleSink) {
        if (passthrough) {
            emitPassthrough(frames, sink)
            return
        }
        historyLength += frames
        inputFrames += frames
        produce(sink)
    }

    private fun emitPassthrough(frames: Int, sink: SampleSink) {
        var from = historyLength
        var left = frames
        while (left > 0) {
            val portion = min(left, OUTPUT_BLOCK - outputLength)
            for (i in 0 until portion) output[outputLength + i] = history[from + i].coerceIn(-1f, 1f)
            outputLength += portion
            from += portion
            left -= portion
            if (outputLength == OUTPUT_BLOCK) drain(sink)
        }
    }

    private fun compact() {
        val keepFrom = (position - historyBase - halfTaps + 1).toInt().coerceIn(0, historyLength)
        history.copyInto(history, 0, keepFrom, historyLength)
        historyLength -= keepFrom
        historyBase += keepFrom
    }

    /** Считает выход, пока для фильтра хватает входа. Нули хвоста новых отсчётов не порождают. */
    private fun produce(sink: SampleSink) {
        var whole = position
        var phaseCounter = fraction
        val available = historyBase + historyLength
        val localTable = table
        val localHistory = history
        val localTaps = taps
        val half = halfTaps
        while (whole + half < available && whole < inputFrames) {
            val phase = if (phases == denominator) {
                phaseCounter
            } else {
                ((phaseCounter.toLong() * phases) / denominator).toInt()
            }
            val rowStart = phase * localTaps
            val first = (whole - historyBase - half + 1).toInt()
            var sum = 0f
            for (k in 0 until localTaps) sum += localTable[rowStart + k] * localHistory[first + k]
            output[outputLength++] = if (sum > 1f) 1f else if (sum < -1f) -1f else sum
            if (outputLength == OUTPUT_BLOCK) drain(sink)
            whole += stepWhole
            phaseCounter += stepFraction
            if (phaseCounter >= denominator) {
                phaseCounter -= denominator
                whole++
            }
        }
        position = whole
        fraction = phaseCounter
    }

    private fun drain(sink: SampleSink) {
        if (outputLength > 0) sink.accept(output, outputLength)
        outputLength = 0
    }

    companion object {
        /** Частота, с которой работают все модели. */
        const val DEFAULT_OUTPUT_RATE = 16_000

        private const val OUTPUT_BLOCK = 4_096
        private const val MIN_HISTORY = 8_192
        private const val MAX_PHASES = 2_048
        private const val ZERO_CROSSINGS = 20
        private const val DOWN_ROLLOFF = 0.92
        private const val UP_ROLLOFF = 0.95
        private const val KAISER_BETA = 8.0
        private const val SHORT_SCALE = 32_768f

        private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        /** Таблица фильтра по фазам. Строка нормирована: постоянный сигнал проходит без изменений. */
        private fun buildTable(phases: Int, halfTaps: Int, cutoff: Double): FloatArray {
            val taps = 2 * halfTaps
            val out = FloatArray(phases * taps)
            val row = DoubleArray(taps)
            val norm = besselI0(KAISER_BETA)
            for (phase in 0 until phases) {
                val shift = phase.toDouble() / phases
                var sum = 0.0
                for (k in 0 until taps) {
                    val t = k - halfTaps + 1 - shift
                    val x = t / halfTaps
                    val window = if (x * x >= 1.0) 0.0 else besselI0(KAISER_BETA * sqrt(1.0 - x * x)) / norm
                    row[k] = sinc(cutoff * t) * cutoff * window
                    sum += row[k]
                }
                for (k in 0 until taps) out[phase * taps + k] = (row[k] / sum).toFloat()
            }
            return out
        }

        private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            var n = 1
            while (term > sum * 1e-12) {
                val factor = x / (2.0 * n)
                term *= factor * factor
                sum += term
                n++
            }
            return sum
        }
    }
}
