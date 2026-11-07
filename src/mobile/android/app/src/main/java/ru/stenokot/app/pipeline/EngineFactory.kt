package ru.stenokot.app.pipeline

import ru.stenokot.core.contract.TranscriptValidator
import ru.stenokot.core.engine.Asr
import ru.stenokot.core.engine.Diarizer
import ru.stenokot.core.engine.EngineFailure
import ru.stenokot.core.engine.LanguageDetector
import ru.stenokot.core.engine.LlmRunner
import ru.stenokot.core.engine.Merger
import ru.stenokot.core.engine.PcmReader
import ru.stenokot.core.engine.Progress
import ru.stenokot.core.engine.Vad
import ru.stenokot.core.model.AudioInfo
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.LlmBudget
import ru.stenokot.core.model.LlmResult
import ru.stenokot.core.model.LlmStatus
import java.io.File

/**
 * Движки, которыми пользуется конвейер. Каждая стадия создаёт свой движок и закрывает его сама,
 * поэтому в памяти никогда не лежат две тяжёлые модели.
 */
interface PipelineEngines {
    /** Отпечаток настроек и моделей. Его смена делает недействительными чекпойнты распознавания. */
    val configHash: String

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

    /** Создаёт диаризатор. */
    fun diarizer(): Diarizer

    /** Создаёт слияние слов и говорящих. */
    fun merger(): Merger

    /** Создаёт проверку контрактных байтов. */
    fun validator(): TranscriptValidator

    /** Запуск языковой модели. */
    val llm: LlmRunner
}

/**
 * Единственная точка подключения настоящих движков.
 *
 * Классы движков появляются в других пакетах, поэтому пока здесь честный отказ: стадия завершается
 * кодом MODEL_LOAD_FAILED, а не притворяется работающей. Подключение делается заменой тел методов.
 */
class EngineFactory : PipelineEngines {
    override val configHash: String = "unwired"

    override suspend fun decode(source: File, outWav: File, progress: Progress): AudioInfo =
        throw unwired("AudioDecoder")

    override fun openPcm(wav: File): PcmReader = throw unwired("PcmReader")

    override fun vad(): Vad = throw unwired("Vad")

    override fun asr(language: Language): Asr = throw unwired("Asr")

    override fun languageDetector(): LanguageDetector = throw unwired("LanguageDetector")

    override fun diarizer(): Diarizer = throw unwired("Diarizer")

    override fun merger(): Merger = throw unwired("Merger")

    override fun validator(): TranscriptValidator = throw unwired("TranscriptValidator")

    override val llm: LlmRunner = object : LlmRunner {
        override suspend fun run(jobDir: File, budget: LlmBudget, progress: Progress): LlmResult =
            LlmResult(LlmStatus.FAILED, null, ErrorCode.LLM_MODEL_UNAVAILABLE.name)

        override fun cancel() = Unit
    }

    private fun unwired(name: String) =
        EngineFailure(ErrorCode.MODEL_LOAD_FAILED, "$name не подключён")
}
