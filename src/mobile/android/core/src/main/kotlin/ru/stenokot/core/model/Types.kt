package ru.stenokot.core.model

/** Сведения о декодированной записи. */
data class AudioInfo(
    val durationMs: Long,
    val sourceSampleRate: Int,
    val sourceChannels: Int,
    val mime: String,
    val sourceSha256: String,
)

/**
 * Итог предварительной проверки файла. Показывается пользователю и не требует подтверждения.
 *
 * [estimateMeasured] означает, что оценка опирается на замер этого устройства. Иначе диапазон
 * примерный и должен быть подписан как примерный.
 */
data class PreflightInfo(
    val fileName: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val mime: String?,
    val freeBytes: Long,
    val requiredBytes: Long,
    val estimateMinMs: Long?,
    val estimateMaxMs: Long?,
    val estimateMeasured: Boolean = false,
)

/** Непрерывный срез записи в отсчётах 16 кГц. */
data class SpeechSegment(val index: Int, val startSample: Long, val endSample: Long)

/** Токен распознавателя со временем начала от начала записи. */
data class AsrToken(val text: String, val startMs: Long)

/** Результат распознавания одного среза. */
data class AsrSegment(val segment: SpeechSegment, val text: String, val tokens: List<AsrToken>)

/** Интервал речи говорящего. Номер говорящего внутренний и начинается с нуля. */
data class SpeakerTurn(val startMs: Long, val endMs: Long, val speaker: Int, val confidence: Float? = null)

/** Слово с закреплённым говорящим. Текст слова после распознавания не меняется. */
data class Word(val text: String, val startMs: Long, val endMs: Long, val speaker: Int)

/** Реплика: неизменяемая база для контракта, интерфейса и протокола. */
data class Utterance(
    val id: Int,
    val speaker: Int,
    val startMs: Long,
    val endMs: Long,
    val words: List<Word>,
    val text: String,
    val overlap: Boolean = false,
)

/** Язык обработки файла. */
enum class Language { RU, EN }

/** Стадии конвейера в порядке выполнения. */
enum class Stage { IMPORT, DECODE, VAD, LANG_ID, ASR, DIARIZE, MERGE, VALIDATE, LLM }

/** Причина временной остановки конвейера. */
enum class PauseReason { THERMAL, QUEUED_BEHIND_OTHER_JOB }

/** Коды ошибок конвейера. Пользователь видит понятный текст, код доступен в диагностике. */
enum class ErrorCode {
    STORAGE_LOW,
    IMPORT_READ_FAILED,
    IMPORT_NO_PERMISSION,
    DECODE_UNSUPPORTED_FORMAT,
    DECODE_CORRUPT,
    AUDIO_EMPTY,
    LANG_ID_FAILED,
    MODEL_LOAD_FAILED,
    LOW_MEMORY,
    CONTRACT_INVALID,
    LLM_MODEL_UNAVAILABLE,
    LLM_LOW_MEMORY,
    LLM_DIED_OOM,
    LLM_DIED_NATIVE,
    LLM_TIMEOUT,
    THERMAL_CRITICAL,
    FGS_TIMEOUT,
    EXPORT_FAILED,
}

/** Итог работы локальной языковой модели. Контрактный файл от него не зависит. */
enum class LlmStatus {
    APPLIED,
    PARTIAL,
    DEFERRED,
    CANCELLED_BY_NEW_JOB,
    SKIPPED_BY_BUDGET,
    SKIPPED_LOW_MEMORY,
    FAILED,
}

/** Ограничения запуска языковой модели. */
data class LlmBudget(val nCtx: Int, val maxChunkTokens: Int, val deadlineMs: Long)

/** Результат стадии языковой модели. */
data class LlmResult(val status: LlmStatus, val protocolPath: String?, val detail: String?)

/** Состояние задачи. Переходы описаны в docs/LOGIC.md. */
sealed interface JobState {
    /** Задача ждёт своей очереди. */
    data object Queued : JobState

    /** Идёт стадия [stage], [fraction] находится в диапазоне от 0 до 1. */
    data class Running(val stage: Stage, val fraction: Float) : JobState

    /** Контрактный файл записан, языковая модель ещё не запускалась. */
    data class TranscriptReady(val path: String) : JobState

    /** Файл готов, идёт копирование модели на диск. */
    data object WaitingModel : JobState

    /** Обработка приостановлена и продолжится сама. */
    data class Paused(val stage: Stage, val reason: PauseReason) : JobState

    /** Обработка прервана, её можно продолжить со стадии [resumeFrom]. */
    data class Interrupted(val resumeFrom: Stage, val code: ErrorCode?) : JobState

    /** Обработка остановлена ошибкой до появления контрактного файла. */
    data class Failed(val stage: Stage, val code: ErrorCode, val detail: String?) : JobState

    /** Задача отменена пользователем. */
    data object Cancelled : JobState

    /** Задача завершена, контрактный файл записан. */
    data class Done(val llm: LlmStatus, val warnings: List<String>) : JobState
}
