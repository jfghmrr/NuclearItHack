package ru.stenokot.app.pipeline

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
import ru.stenokot.core.model.LlmBudget
import ru.stenokot.core.model.LlmResult
import ru.stenokot.core.model.LlmStatus
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
 * Единственная точка подключения настоящих движков.
 *
 * Чистая логика ядра подключена. Классы аудио и языковой модели приходят из других веток, поэтому
 * до их появления здесь честный отказ: стадия завершается кодом MODEL_LOAD_FAILED, а не притворяется
 * работающей. Подключение делается заменой тел методов.
 */
class EngineFactory : PipelineEngines {
    override val configHash: String = "unwired"

    override fun probe(source: File): SourceProbe = throw unwired("Preflight")

    override suspend fun decode(source: File, outWav: File, progress: Progress): AudioInfo =
        throw unwired("AudioDecoder")

    override fun openPcm(wav: File): PcmReader = throw unwired("PcmReader")

    override fun vad(): Vad = throw unwired("Vad")

    override fun asr(language: Language): Asr = throw unwired("Asr")

    override fun languageDetector(): LanguageDetector = LanguageVote()

    override fun diarizer(request: DiarizationRequest): Diarizer = throw unwired("Diarizer")

    override fun merger(audioDurationMs: Long): Merger = DefaultMerger(audioDurationMs)

    override fun validator(): TranscriptValidator = DefaultTranscriptValidator()

    override val llm: LlmRunner = object : LlmRunner {
        override suspend fun run(jobDir: File, budget: LlmBudget, progress: Progress): LlmResult =
            LlmResult(LlmStatus.FAILED, null, ErrorCode.LLM_MODEL_UNAVAILABLE.name)

        override fun cancel() = Unit
    }

    private fun unwired(name: String) =
        EngineFailure(ErrorCode.MODEL_LOAD_FAILED, "$name не подключён")
}
