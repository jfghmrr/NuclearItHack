package ru.stenokot.app.speech

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import ru.stenokot.core.diar.TimeSpan
import ru.stenokot.core.diar.VoicePrinter
import ru.stenokot.core.engine.PcmReader

/**
 * Отпечатки фраз загруженной моделью диаризатора: второй загрузки нет. Звук каждой фразы читается
 * отдельно и сразу отпускается, поэтому память не зависит от длины записи.
 */
internal class PhrasePrinter(
    private val pcm: PcmReader,
    private val extractor: SpeakerEmbeddingExtractor,
    private val isCancelled: () -> Boolean,
) : VoicePrinter {

    override fun print(spans: List<TimeSpan>): FloatArray? {
        if (isCancelled()) throw WindowedDiarizer.DiarizationCancelled()
        val samples = samples(spans)
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            if (!extractor.isReady(stream)) return null
            return extractor.compute(stream).takeIf { values -> values.isNotEmpty() && values.none { it.isNaN() } }
        } finally {
            stream.release()
        }
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
    }
}
