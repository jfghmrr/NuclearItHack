package ru.stenokot.app.speech

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import ru.stenokot.core.diar.SpeakerRefiner
import ru.stenokot.core.diar.TimeSpan
import ru.stenokot.core.diar.VoicePrinter
import ru.stenokot.core.engine.PcmReader
import ru.stenokot.core.engine.Progress
import ru.stenokot.core.engine.TurnRefiner
import ru.stenokot.core.merge.DefaultMerger
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.SpeakerTurn
import ru.stenokot.core.storage.DiarJournalLine

/**
 * Настоящие отпечатки для [SpeakerRefiner]: звук каждой фразы читается отдельно и сразу отпускается,
 * поэтому память не зависит от длины записи. Модель живёт только внутри [refine].
 */
class PhraseRefiner(
    private val models: ModelSource,
    private val audioDurationMs: Long,
    private val isCancelled: () -> Boolean,
    private val threads: Int = EngineThreads.diarization(),
) : TurnRefiner {

    override fun refine(
        pcm: PcmReader,
        asr: List<AsrSegment>,
        turns: List<SpeakerTurn>,
        lines: List<DiarJournalLine>,
        progress: Progress,
    ): List<SpeakerTurn> {
        val refiner = SpeakerRefiner()
        val words = DefaultMerger(audioDurationMs).assignWords(asr, turns)
        val overlaps = SpeakerRefiner.overlaps(lines)
        val phrases = refiner.phrases(words, overlaps)
        if (phrases.isEmpty()) return turns
        models.require(ModelFiles.DIAR_EMBEDDING)
        val extractor = loadModel(ModelFiles.DIAR_EMBEDDING) {
            SpeakerEmbeddingExtractor(
                assetManager = models.assets,
                config = SpeakerEmbeddingExtractorConfig(
                    model = models.path(ModelFiles.DIAR_EMBEDDING),
                    numThreads = threads,
                ),
            )
        }
        try {
            return refiner.refine(phrases, turns, overlaps, printer(pcm, extractor), progress)
        } finally {
            extractor.release()
        }
    }

    override fun close() = Unit

    private fun printer(pcm: PcmReader, extractor: SpeakerEmbeddingExtractor) = VoicePrinter { spans ->
        if (isCancelled()) throw WindowedDiarizer.DiarizationCancelled()
        print(samples(pcm, spans), extractor)
    }

    private fun samples(pcm: PcmReader, spans: List<TimeSpan>): FloatArray {
        val buffer = FloatArray(spans.sumOf { (it.lengthMs * SAMPLES_PER_MS).toInt() })
        var filled = 0
        for (span in spans) {
            val part = pcm.read(span.startMs * SAMPLES_PER_MS, (span.lengthMs * SAMPLES_PER_MS).toInt())
            System.arraycopy(part, 0, buffer, filled, part.size)
            filled += part.size
        }
        return if (filled == buffer.size) buffer else buffer.copyOf(filled)
    }

    private fun print(samples: FloatArray, extractor: SpeakerEmbeddingExtractor): FloatArray? {
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

    private companion object {
        const val SAMPLE_RATE = 16000
        const val SAMPLES_PER_MS = 16L
    }
}
