package ru.stenokot.app.pipeline

import android.content.Context
import ru.stenokot.app.audio.EngineException
import ru.stenokot.app.audio.MediaDecoder
import ru.stenokot.app.audio.Preflight
import ru.stenokot.app.audio.WavPcmReader
import ru.stenokot.app.llm.RemoteLlmRunner
import ru.stenokot.app.speech.DiarizationJournal
import ru.stenokot.app.speech.GigaAsr
import ru.stenokot.app.speech.ModelSource
import ru.stenokot.app.speech.ParakeetAsr
import ru.stenokot.app.speech.SileroVadEngine
import ru.stenokot.app.speech.WindowedDiarizer
import ru.stenokot.core.contract.DefaultTranscriptValidator
import ru.stenokot.core.contract.TranscriptValidator
import ru.stenokot.core.engine.Asr
import ru.stenokot.core.engine.Diarizer
import ru.stenokot.core.engine.LanguageDetector
import ru.stenokot.core.engine.LlmRunner
import ru.stenokot.core.engine.Merger
import ru.stenokot.core.engine.PcmReader
import ru.stenokot.core.engine.Progress
import ru.stenokot.core.engine.Vad
import ru.stenokot.core.lang.LanguageVote
import ru.stenokot.core.merge.DefaultMerger
import ru.stenokot.core.model.AudioInfo
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.Language
import ru.stenokot.core.storage.DiarJournalLine
import java.io.File

/** Что известно о файле после пробного чтения. Модели к этому моменту не загружены. */
data class SourceProbe(val durationMs: Long?, val mime: String?)

/** Запрос на диаризацию с продолжением: окна из [restored] повторно не считаются. */
class DiarizationRequest(
    val restored: List<DiarJournalLine>,
    val onWindow: (DiarJournalLine) -> Unit,
    val isCancelled: () -> Boolean,
    val sourceSampleRate: Int?,
)

/**
 * Движки, которыми пользуется конвейер. Каждая стадия создаёт свой движок и закрывает его сама,
 * поэтому в памяти никогда не лежат две тяжёлые модели.
 */
interface PipelineEngines {
    /** Отпечаток настроек и моделей. Его смена отменяет всё после декодирования. */
    val configHash: String

    /** Проверяет формат, кодек и читаемость копии файла до загрузки моделей. */
    fun probe(source: File): SourceProbe

    /** Декодирует [source] в нормализованный WAV 16 кГц моно. */
    suspend fun decode(source: File, outWav: File, progress: Progress): AudioInfo

    /** Открывает нормализованный WAV для чтения. */
    fun openPcm(wav: File): PcmReader

    /** Создаёт определитель речи. */
    fun vad(): Vad

    /** Создаёт распознаватель для языка [language]. */
    fun asr(language: Language): Asr

    /** Создаёт определитель языка. */
    fun languageDetector(): LanguageDetector

    /** Создаёт диаризатор, который пишет чекпойнт после каждого окна. */
    fun diarizer(request: DiarizationRequest): Diarizer

    /** Создаёт слияние слов и говорящих для записи длиной [audioDurationMs]. */
    fun merger(audioDurationMs: Long): Merger

    /** Создаёт проверку контрактных байтов. */
    fun validator(): TranscriptValidator

    /** Запуск языковой модели. */
    val llm: LlmRunner

    /** Возвращает код ошибки из исключения движка или null, если сбой без кода. */
    fun errorCode(failure: Throwable): ErrorCode? = (failure as? EngineFailure)?.code
}

/**
 * Единственная точка подключения настоящих движков. Каждый метод создаёт новый объект: стадия сама
 * закрывает свой движок, и тяжёлая модель не переживает стадию. Частота источника до 11 кГц включает
 * узкополосный режим книги говорящих.
 */
class EngineFactory(context: Context, modelSetVersion: String) : PipelineEngines {
    private val app = context.applicationContext
    private val models by lazy { ModelSource.from(app) }

    override val configHash: String = modelSetVersion

    override val llm: LlmRunner = RemoteLlmRunner(app)

    override fun probe(source: File): SourceProbe =
        Preflight.probe(source).let { SourceProbe(it.headerDurationMs, it.mime) }

    override suspend fun decode(source: File, outWav: File, progress: Progress): AudioInfo =
        MediaDecoder().decode(source, outWav, progress)

    override fun openPcm(wav: File): PcmReader = WavPcmReader.open(wav)

    override fun vad(): Vad = SileroVadEngine(models)

    override fun asr(language: Language): Asr =
        if (language == Language.EN) ParakeetAsr(models) else GigaAsr(models)

    override fun languageDetector(): LanguageDetector = LanguageVote()

    override fun diarizer(request: DiarizationRequest): Diarizer = WindowedDiarizer(
        models = models,
        narrowband = (request.sourceSampleRate ?: Int.MAX_VALUE) <= NARROWBAND_MAX_RATE,
        journal = DiarizationJournal(request.restored, request.onWindow),
        isCancelled = request.isCancelled,
    )

    override fun merger(audioDurationMs: Long): Merger = DefaultMerger(audioDurationMs)

    override fun validator(): TranscriptValidator = DefaultTranscriptValidator()

    override fun errorCode(failure: Throwable): ErrorCode? =
        (failure as? EngineFailure)?.code ?: (failure as? EngineException)?.code

    private companion object {
        const val NARROWBAND_MAX_RATE = 11_025
    }
}
