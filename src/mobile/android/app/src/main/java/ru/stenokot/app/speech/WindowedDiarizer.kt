package ru.stenokot.app.speech

import androidx.annotation.Keep
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import ru.stenokot.core.diar.SpeakerBook
import ru.stenokot.core.engine.Diarizer
import ru.stenokot.core.engine.PcmReader
import ru.stenokot.core.engine.Progress
import ru.stenokot.core.model.SpeakerTurn
import ru.stenokot.core.storage.DiarClusterRecord
import ru.stenokot.core.storage.DiarJournalLine
import ru.stenokot.core.storage.DiarTurnRecord

/** Что нужно от конвейера: продолжение после сбоя и запись чекпойнта окна. */
class DiarizationJournal(
    /** Окна, уже записанные в diar.jsonl. Обработка продолжается со следующего окна. */
    val restored: List<DiarJournalLine> = emptyList(),
    /** Вызывается после каждого окна до перехода к следующему. Здесь строка уходит в журнал. */
    val onWindow: (DiarJournalLine) -> Unit = {},
)

/**
 * Диаризация окнами по 5 минут с нахлёстом 10 с: одна ветка кода для любой длины. Нативный Process
 * держит весь сигнал в куче и строит квадратичную матрицу, поэтому часовой файл целиком не подаётся.
 * Кластеры окон связываются в [SpeakerBook] по эмбеддингам. Обе модели живут только внутри [diarize],
 * поэтому после возврата нативная память уже освобождена. Шаг окна сегментатора зависит от длины
 * записи и задаётся при создании модели, а задать его позже нельзя.
 */
class WindowedDiarizer(
    private val models: ModelSource,
    private val threads: Int = EngineThreads.diarization(),
    private val journal: DiarizationJournal = DiarizationJournal(),
    private val isCancelled: () -> Boolean = { false },
    /** Порог кластеризации внутри окна: косинусное расстояние, ниже которого кластеры сливаются. */
    private val threshold: Float = DEFAULT_THRESHOLD,
    /** Наибольшая длина речи для отпечатка, секунды. Память извлекателя растёт вместе с ней. */
    embeddingSeconds: Int = MAX_EMBEDDING_SECONDS,
) : Diarizer {
    private val maxEmbeddingSamples = embeddingSeconds * SAMPLE_RATE


    override fun diarize(pcm: PcmReader, progress: Progress): List<SpeakerTurn> {
        val total = pcm.totalSamples
        if (total == 0L) return emptyList()
        val book = SpeakerBook()
        val linked = journal.restored.mapTo(ArrayList()) { book.addWindow(it) }
        val windows = planWindows(total)
        if (journal.restored.size < windows.size) {
            models.require(ModelFiles.DIAR_SEGMENTATION, ModelFiles.DIAR_EMBEDDING)
            withModels(total) { diarizer, extractor ->
                runWindows(pcm, windows, diarizer, extractor, book, linked, progress)
            }
        }
        return book.finish(linked)
    }

    override fun close() = Unit

    private inline fun withModels(
        totalSamples: Long,
        block: (OfflineSpeakerDiarization, SpeakerEmbeddingExtractor) -> Unit,
    ) {
        val shift = if (totalSamples < SHORT_RECORDING_SAMPLES) SHORT_SHIFT_RATIO else SHIFT_RATIO
        val embedding = SpeakerEmbeddingExtractorConfig(
            model = models.path(ModelFiles.DIAR_EMBEDDING),
            numThreads = threads,
        )
        val diarizer = loadModel(ModelFiles.DIAR_SEGMENTATION) {
            OfflineSpeakerDiarization(
                assetManager = models.assets,
                config = OfflineSpeakerDiarizationConfig(
                    segmentation = OfflineSpeakerSegmentationModelConfig(
                        pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                            model = models.path(ModelFiles.DIAR_SEGMENTATION),
                            windowShiftRatio = shift,
                        ),
                        numThreads = threads,
                    ),
                    embedding = embedding,
                    clustering = FastClusteringConfig(threshold = threshold, computeConfidence = true),
                    minDurationOn = MIN_DURATION_ON,
                    minDurationOff = MIN_DURATION_OFF,
                ),
            )
        }
        try {
            val extractor = loadModel(ModelFiles.DIAR_EMBEDDING) {
                SpeakerEmbeddingExtractor(assetManager = models.assets, config = embedding)
            }
            try {
                block(diarizer, extractor)
            } finally {
                extractor.release()
            }
        } finally {
            diarizer.release()
        }
    }

    private fun runWindows(
        pcm: PcmReader,
        windows: List<DiarWindow>,
        diarizer: OfflineSpeakerDiarization,
        extractor: SpeakerEmbeddingExtractor,
        book: SpeakerBook,
        linked: MutableList<List<SpeakerTurn>>,
        progress: Progress,
    ) {
        for (index in journal.restored.size until windows.size) {
            if (isCancelled()) throw DiarizationCancelled()
            val window = windows[index]
            val samples = pcm.read(window.startSample, (window.endSample - window.startSample).toInt())
            val segments = diarizer.processWithCallback(samples, WindowProgress(progress, index, windows.size))
            val line = describeWindow(index, window, samples, segments, extractor)
            journal.onWindow(line)
            linked += book.addWindow(line)
            progress.report((index + 1f) / windows.size, "${index + 1}/${windows.size}")
        }
    }

    private fun describeWindow(
        index: Int,
        window: DiarWindow,
        samples: FloatArray,
        segments: Array<OfflineSpeakerDiarizationSegment>,
        extractor: SpeakerEmbeddingExtractor,
    ): DiarJournalLine {
        val originMs = samplesToMs(window.startSample)
        val turns = segments.map {
            DiarTurnRecord(
                cluster = it.speaker,
                startMs = originMs + secondsToMs(it.start),
                endMs = originMs + secondsToMs(it.end),
                confidence = it.confidence.takeIf { value -> value >= MIN_CONFIDENCE },
            )
        }
        val clusters = segments.groupBy { it.speaker }.toSortedMap().map { (cluster, own) ->
            val others = segments.filter { it.speaker != cluster }
            DiarClusterRecord(
                cluster = cluster,
                speechMs = own.sumOf { secondsToMs(it.end) - secondsToMs(it.start) },
                embedding = embed(own, others, samples, extractor)?.toList(),
            )
        }
        return DiarJournalLine(index, samplesToMs(window.keepFrom), samplesToMs(window.keepTo), turns, clusters)
    }

    /**
     * Отпечаток голоса кластера по 1.5-30 с речи, вне этого диапазона нативный извлекатель падал.
     * Берётся чистая речь без наложений, самые длинные куски первыми. Если чистой речи мало,
     * кластер остаётся без отпечатка и книга говорящих привяжет его по времени.
     */
    private fun embed(
        own: List<OfflineSpeakerDiarizationSegment>,
        others: List<OfflineSpeakerDiarizationSegment>,
        samples: FloatArray,
        extractor: SpeakerEmbeddingExtractor,
    ): FloatArray? {
        val spans = cleanSpans(own, others, samples.size).sortedByDescending { it.last - it.first }
        val buffer = FloatArray(min(maxEmbeddingSamples, spans.sumOf { it.last - it.first + 1 }))
        var filled = 0
        for (span in spans) {
            if (filled == buffer.size) break
            val part = min(span.last - span.first + 1, buffer.size - filled)
            System.arraycopy(samples, span.first, buffer, filled, part)
            filled += part
        }
        if (filled < MIN_EMBEDDING_SAMPLES) return null
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(buffer, SAMPLE_RATE)
            stream.inputFinished()
            if (!extractor.isReady(stream)) return null
            return extractor.compute(stream).takeIf { values -> values.isNotEmpty() && values.none { it.isNaN() } }
        } finally {
            stream.release()
        }
    }

    /** Участки кластера в отсчётах окна, из которых вычтена речь других кластеров. */
    private fun cleanSpans(
        own: List<OfflineSpeakerDiarizationSegment>,
        others: List<OfflineSpeakerDiarizationSegment>,
        limit: Int,
    ): List<IntRange> {
        val blocked = others.sortedBy { it.start }.map { secondsToSample(it.start, limit) to secondsToSample(it.end, limit) }
        val spans = ArrayList<IntRange>()
        for (segment in own) {
            var from = secondsToSample(segment.start, limit)
            val to = secondsToSample(segment.end, limit)
            for ((blockFrom, blockTo) in blocked) {
                if (blockFrom >= to) break
                if (blockTo <= from) continue
                if (blockFrom > from) spans += from until blockFrom
                from = max(from, blockTo)
            }
            if (from < to) spans += from until to
        }
        return spans
    }

    /** Отмена между окнами: сам нативный вызов на окне неделим. */
    class DiarizationCancelled : Exception("Отменено")

    /** Окно записи в отсчётах 16 кГц. [keepFrom] и [keepTo] задают участок без нахлёста с соседями. */
    internal data class DiarWindow(
        val startSample: Long,
        val endSample: Long,
        val keepFrom: Long,
        val keepTo: Long,
    )

    companion object {
        /**
         * Порог подобран на русских диалогах с известной разметкой. Значение 0.9 с английских совещаний
         * сводило двух собеседников в одного на записях до полутора минут, монолог не дробится и при 0.3.
         */
        const val DEFAULT_THRESHOLD = 0.6f

        private const val SAMPLE_RATE = 16000
        private const val WINDOW_SAMPLES = 300L * SAMPLE_RATE
        private const val OVERLAP_SAMPLES = 10L * SAMPLE_RATE
        private const val TAIL_MERGE_SAMPLES = 30L * SAMPLE_RATE
        private const val SHORT_RECORDING_SAMPLES = 180L * SAMPLE_RATE
        private const val SHIFT_RATIO = 0.25f
        private const val SHORT_SHIFT_RATIO = 0.2f
        private const val MIN_DURATION_ON = 0.1f
        private const val MIN_DURATION_OFF = 0.1f
        private const val MIN_EMBEDDING_SAMPLES = 3 * SAMPLE_RATE / 2
        private const val MAX_EMBEDDING_SECONDS = 30
        private const val MIN_CONFIDENCE = -1f

        /**
         * Окна записи. Короткий хвост присоединяется к последнему окну: в нём мало речи для отпечатка.
         * Граница ответственности соседних окон проходит посередине нахлёста.
         */
        internal fun planWindows(totalSamples: Long): List<DiarWindow> {
            val step = WINDOW_SAMPLES - OVERLAP_SAMPLES
            val bounds = ArrayList<LongRange>()
            var start = 0L
            while (start + WINDOW_SAMPLES + TAIL_MERGE_SAMPLES < totalSamples) {
                bounds += start until start + WINDOW_SAMPLES
                start += step
            }
            bounds += start until totalSamples
            return bounds.mapIndexed { index, range ->
                val end = range.last + 1
                val keepFrom = if (index == 0) 0L else (range.first + bounds[index - 1].last + 1) / 2
                val keepTo = if (index == bounds.lastIndex) end else (bounds[index + 1].first + end) / 2
                DiarWindow(range.first, end, keepFrom, keepTo)
            }
        }

        private fun secondsToSample(seconds: Float, limit: Int): Int =
            (seconds * SAMPLE_RATE).roundToInt().coerceIn(0, limit)

        private fun secondsToMs(seconds: Float): Long = (seconds.toDouble() * 1000).roundToLong()

        private fun samplesToMs(samples: Long): Long = samples * 1000 / SAMPLE_RATE
    }
}

/**
 * Прогресс окна из нативного кода. Обязательно отдельный класс, а не лямбда: JNI ищет метод
 * invoke(IIJ)Ljava/lang/Integer; у самого объекта, а у лямбды его нет, и процесс падает с SIGABRT.
 */
@Keep
private class WindowProgress(
    private val progress: Progress,
    private val window: Int,
    private val windows: Int,
) : (Int, Int, Long) -> Int {
    private var lastReport = 0L

    override fun invoke(done: Int, all: Int, arg: Long): Int {
        val now = System.currentTimeMillis()
        if (all > 0 && now - lastReport >= REPORT_EVERY_MS) {
            lastReport = now
            progress.report((window + done.toFloat() / all) / windows, "$window/$windows")
        }
        return 0
    }

    private companion object {
        const val REPORT_EVERY_MS = 1000L
    }
}
