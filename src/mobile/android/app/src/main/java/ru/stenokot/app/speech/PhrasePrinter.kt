package ru.stenokot.app.speech

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import ru.stenokot.core.diar.TimeSpan
import ru.stenokot.core.diar.VoicePrinter
import kotlin.math.log10
import ru.stenokot.core.engine.PcmReader

/**
 * Отпечатки фраз моделью диаризатора: второй загрузки нет, а если отпечатки не понадобились, нет и первой.
 * Звук каждой фразы читается отдельно и сразу отпускается, поэтому память не зависит от длины записи.
 */
internal class PhrasePrinter(
    private val pcm: PcmReader,
    private val extractor: Lazy<SpeakerEmbeddingExtractor>,
    private val isCancelled: () -> Boolean,
) : VoicePrinter {

    override fun print(spans: List<TimeSpan>): FloatArray? {
        if (isCancelled()) throw WindowedDiarizer.DiarizationCancelled()
        val samples = samples(spans)
        val model = extractor.value
        val stream = model.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            if (!model.isReady(stream)) return null
            return model.compute(stream).takeIf { values -> values.isNotEmpty() && values.none { it.isNaN() } }
        } finally {
            stream.release()
        }
    }

    /** Средняя энергия громкой половины кадров по 20 мс: паузы внутри отрезка не занижают уровень. */
    override fun levelDb(span: TimeSpan): Float? {
        val samples = samples(listOf(span))
        val frames = samples.size / FRAME_SAMPLES
        if (frames == 0) return null
        val energy = DoubleArray(frames) { frame ->
            var sum = 0.0
            for (i in frame * FRAME_SAMPLES until (frame + 1) * FRAME_SAMPLES) sum += samples[i].toDouble() * samples[i]
            sum / FRAME_SAMPLES
        }
        energy.sort()
        val loud = energy.copyOfRange(frames / 2, frames)
        return (10 * log10(loud.average() + LEVEL_FLOOR)).toFloat()
    }

    private fun samples(spans: List<TimeSpan>): FloatArray {
        val buffer = FloatArray(spans.sumOf { (it.lengthMs * SAMPLES_PER_MS).toInt() })
        var filled = 0
        for (span in spans) {
            val part = pcm.read(span.startMs * SAMPLES_PER_MS, (span.lengthMs * SAMPLES_PER_MS).toInt())
            System.arraycopy(part, 0, buffer, filled, part.size)
            filled += part.size
        }
        return if (filled == buffer.size) buffer else buffer.copyOf(filled)
    }

    private companion object {
        const val SAMPLE_RATE = 16000
        const val SAMPLES_PER_MS = 16L
        const val FRAME_SAMPLES = 320
        const val LEVEL_FLOOR = 1e-12
    }
}
