package ru.stenokot.app.data

import kotlinx.serialization.Serializable
import ru.stenokot.core.model.AudioInfo
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.PauseReason
import ru.stenokot.core.model.PreflightInfo
import ru.stenokot.core.model.Stage
import ru.stenokot.core.storage.LanguageVotes

/** Положение задачи на диске. Живая доля прогресса сюда не входит и в job.json не пишется. */
@Serializable
enum class JobPhase {
    QUEUED,
    RUNNING,
    PAUSED,
    TRANSCRIPT_READY,
    WAITING_MODEL,
    INTERRUPTED,
    FAILED,
    CANCELLED,
    DONE,
}

/** Сериализуемая форма [PreflightInfo]. */
@Serializable
data class PreflightRecord(
    val fileName: String,
    val sizeBytes: Long,
    val durationMs: Long? = null,
    val mime: String? = null,
    val freeBytes: Long,
    val requiredBytes: Long,
    val estimateMinMs: Long? = null,
    val estimateMaxMs: Long? = null,
    val estimateMeasured: Boolean = false,
) {
    /** Переводит запись в тип интерфейса. */
    fun toInfo(): PreflightInfo = PreflightInfo(
        fileName = fileName,
        sizeBytes = sizeBytes,
        durationMs = durationMs,
        mime = mime,
        freeBytes = freeBytes,
        requiredBytes = requiredBytes,
        estimateMinMs = estimateMinMs,
        estimateMaxMs = estimateMaxMs,
        estimateMeasured = estimateMeasured,
    )
}

/** Сериализуемая форма [AudioInfo]. */
@Serializable
data class AudioRecord(
    val durationMs: Long,
    val sourceSampleRate: Int,
    val sourceChannels: Int,
    val mime: String,
    val sourceSha256: String,
)

/** Переводит сведения о записи в сериализуемую форму. */
fun AudioInfo.toRecord(): AudioRecord =
    AudioRecord(durationMs, sourceSampleRate, sourceChannels, mime, sourceSha256)

/** Опубликованный контрактный файл. */
@Serializable
data class OutputRecord(
    val path: String,
    val uri: String? = null,
    val sizeBytes: Long,
    val createdAtMs: Long,
    val validated: Boolean,
    val sha256: String,
    val exportFailed: Boolean = false,
    val fallback: Boolean = false,
)

/** Состояние языковой модели для задачи. [status] равен null, пока модель не запускалась. */
@Serializable
data class LlmRecord(
    val status: LlmStatus? = null,
    val errorCode: ErrorCode? = null,
    val detail: String? = null,
    val retryUsed: Boolean = false,
    val protocolPath: String? = null,
    val tookMs: Long = 0,
    val promptTokens: Long? = null,
    val completionTokens: Long? = null,
    val windowsDone: Int? = null,
    val windowsTotal: Int? = null,
)

/**
 * Единственный источник правды о задаче. Пишет его только основной процесс. [deaths] считает смерти
 * процесса на этой задаче, [manualResume] запрещает продолжать её без нажатия «Продолжить».
 */
@Serializable
data class JobRecord(
    val id: String,
    val seq: Long,
    val title: String,
    val stem: String,
    val sourceUri: String,
    val createdAtMs: Long,
    val phase: JobPhase = JobPhase.QUEUED,
    val stage: Stage = Stage.IMPORT,
    val pauseReason: PauseReason? = null,
    val errorCode: ErrorCode? = null,
    val errorDetail: String? = null,
    val completedStages: Set<Stage> = emptySet(),
    val preflight: PreflightRecord? = null,
    val sourceFile: String? = null,
    val sourceInPlace: Boolean = false,
    val audio: AudioRecord? = null,
    val language: Language? = null,
    val langFailed: Boolean = false,
    val langVotes: LanguageVotes? = null,
    val speakers: Int? = null,
    val utterances: Int? = null,
    val noSpeech: Boolean = false,
    val warnings: List<String> = emptyList(),
    val skippedSegments: List<Int> = emptyList(),
    val skippedWindows: List<Int> = emptyList(),
    val output: OutputRecord? = null,
    val llm: LlmRecord = LlmRecord(),
    val stageMs: Map<Stage, Long> = emptyMap(),
    val coreMs: Long = 0,
    val overheadMs: Long = 0,
    val peakRssKb: Long? = null,
    val configHash: String? = null,
    val deaths: Int = 0,
    val manualResume: Boolean = false,
    val resumed: Boolean = false,
) {
    /** Задача ждёт основного потока конвейера. */
    val awaitsWorker: Boolean
        get() = phase == JobPhase.QUEUED || (phase == JobPhase.RUNNING && stage == Stage.IMPORT)

    /** Контрактный файл уже записан. */
    val transcriptSaved: Boolean
        get() = Stage.VALIDATE in completedStages

    /** Длительность записи по лучшему из известных источников. */
    val durationMs: Long?
        get() = audio?.durationMs ?: preflight?.durationMs

    /** Добавляет предупреждение один раз. */
    fun warned(code: String): JobRecord =
        if (code in warnings) this else copy(warnings = warnings + code)
}

/** Стадии до контрактного файла в порядке выполнения. */
val CoreStages: List<Stage> = Stage.entries.filter { it != Stage.LLM }

/** Текстовая метка потерянного результата: так интерфейс отличает её от прерванной обработки. */
const val RESULT_LOST_DETAIL = "RESULT_LOST"

/** Строит состояние для интерфейса. [fraction] берётся из живого прогресса. */
fun JobRecord.toState(fraction: Float = 0f): JobState = when (phase) {
    JobPhase.QUEUED -> JobState.Queued
    JobPhase.RUNNING -> JobState.Running(stage, fraction)
    JobPhase.PAUSED -> JobState.Paused(stage, pauseReason ?: PauseReason.THERMAL)
    JobPhase.TRANSCRIPT_READY -> JobState.TranscriptReady(output?.path.orEmpty())
    JobPhase.WAITING_MODEL -> JobState.WaitingModel
    JobPhase.INTERRUPTED -> JobState.Interrupted(stage, errorCode)
    JobPhase.FAILED -> JobState.Failed(stage, errorCode ?: ErrorCode.CONTRACT_INVALID, errorDetail)
    JobPhase.CANCELLED -> JobState.Cancelled
    JobPhase.DONE -> JobState.Done(llm.status ?: LlmStatus.SKIPPED_BY_BUDGET, warnings)
}
