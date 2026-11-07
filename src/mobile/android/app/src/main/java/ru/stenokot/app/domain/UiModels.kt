package ru.stenokot.app.domain

import androidx.compose.runtime.Immutable
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.PreflightInfo
import ru.stenokot.core.model.Stage
import ru.stenokot.core.model.Utterance
import ru.stenokot.core.protocol.ProtocolDocument

/** Готовность моделей, как её видит пользователь. */
enum class ModelReadiness { READY, COPYING, LLM_UNAVAILABLE, ASR_UNAVAILABLE, STORAGE_LOW }

/** Строка экрана моделей. */
@Immutable
data class ModelItemUi(
    val name: String,
    val role: String,
    val sizeBytes: Long,
    val sha256: String?,
    val license: String,
    val ready: Boolean,
)

/** Состояние моделей и диска. */
@Immutable
data class ModelsUi(
    val readiness: ModelReadiness,
    val copiedBytes: Long,
    val totalBytes: Long,
    val freeBytes: Long,
    val items: List<ModelItemUi>,
)

/** Найденный на устройстве аудиофайл. */
@Immutable
data class AudioFileUi(
    val uri: String,
    val name: String,
    val durationMs: Long?,
    val sizeBytes: Long,
    val folder: String,
)

/** Строка очереди и истории. */
@Immutable
data class JobSummaryUi(
    val id: String,
    val title: String,
    val createdAtMs: Long,
    val durationMs: Long?,
    val speakers: Int?,
    val state: JobState,
    val queuePosition: Int?,
)

/** Этапы, которые видит пользователь. Каждый объединяет одну или несколько стадий конвейера. */
enum class UserStage(val stages: Set<Stage>) {
    PREPARE_AUDIO(setOf(Stage.IMPORT, Stage.DECODE, Stage.VAD, Stage.LANG_ID)),
    RECOGNIZE(setOf(Stage.ASR)),
    DIARIZE(setOf(Stage.DIARIZE)),
    SAVE_TRANSCRIPT(setOf(Stage.MERGE, Stage.VALIDATE)),
    BUILD_PROTOCOL(setOf(Stage.LLM)),
}

/** Положение этапа в обработке. */
enum class StageStatus { PENDING, ACTIVE, DONE, SKIPPED, FAILED }

/**
 * Строка этапа. Счётчик и доля есть только там, где они реально известны.
 * Единица счётчика зависит от стадии конвейера: при копировании файла это КиБ, при декодировании
 * и поиске речи секунды записи, при определении языка и распознавании фрагменты, при определении
 * говорящих окна.
 */
@Immutable
data class StageUi(
    val stage: UserStage,
    val status: StageStatus,
    val fraction: Float?,
    val counterDone: Int?,
    val counterTotal: Int?,
    val tookMs: Long?,
)

/** Экран обработки. Оценка оставшегося времени отсутствует, пока её нечем подтвердить. */
@Immutable
data class ProcessingUi(
    val preflight: PreflightInfo?,
    val stages: List<StageUi>,
    val elapsedMs: Long,
    val remainingMinMs: Long?,
    val remainingMaxMs: Long?,
    val lowMemory: Boolean,
    val cancelling: Boolean,
    val powerSave: Boolean = false,
    val resumed: Boolean = false,
)

/** Сведения о сохранённом контрактном файле. [contentUri] нужен для «Поделиться». */
@Immutable
data class OutputFileUi(
    val path: String,
    val sizeBytes: Long,
    val createdAtMs: Long,
    val validated: Boolean,
    val sha256: String?,
    val exportFailed: Boolean,
    val contentUri: String? = null,
)

/** Состояние протокола в результате. */
@Immutable
data class ProtocolUi(
    val status: LlmStatus?,
    val running: Boolean,
    val windowsDone: Int?,
    val windowsTotal: Int?,
    val modelCopiedBytes: Long?,
    val modelTotalBytes: Long?,
    val retryUsed: Boolean,
    val document: ProtocolDocument?,
    val errorCode: ErrorCode?,
)

/** Экран результата. Реплики уже готовы к показу и не меняются. */
@Immutable
data class ResultUi(
    val title: String,
    val durationMs: Long,
    val language: Language,
    val speakers: Int,
    val utterances: List<Utterance>,
    val audioPath: String?,
    val output: OutputFileUi,
    val protocol: ProtocolUi,
)

/** Подробности задачи: обработка, результат или ошибка. */
@Immutable
data class JobDetailUi(
    val summary: JobSummaryUi,
    val processing: ProcessingUi?,
    val result: ResultUi?,
    val diagnostics: String?,
)

/** Замеры стадии для экрана проверки. */
@Immutable
data class StageMetricUi(val stage: Stage, val tookMs: Long, val rtf: Double?)

/**
 * Экран проверки: доказательства работы на устройстве. Поле со значением null показывается как
 * «не измерено», поэтому ни одно значение нельзя подставлять без настоящего замера.
 */
@Immutable
data class VerificationUi(
    val internetPermissionAbsent: Boolean,
    val networkStatePermissionAbsent: Boolean,
    val appVersion: String,
    val models: List<ModelItemUi>,
    val lastJobTitle: String?,
    val audioDurationMs: Long?,
    val processingMs: Long?,
    val rtfCore: Double?,
    val rtfFull: Double?,
    val peakRssMb: Long?,
    val stageMetrics: List<StageMetricUi>,
    val jsonValid: Boolean?,
    val jsonSha256: String?,
    val jsonPath: String?,
    val llmTokens: Long?,
    val lastJobId: String? = null,
    val threads: Int? = null,
    val llmStatus: LlmStatus? = null,
)

/** Состояние главного экрана. */
@Immutable
data class HomeUi(
    val models: ModelsUi,
    val audioPermissionGranted: Boolean,
    val audio: List<AudioFileUi>,
    val jobs: List<JobSummaryUi>,
    val statusCode: String,
)

/** Что именно экспортируется. */
enum class ExportKind { TRANSCRIPT, METADATA, PROTOCOL }

/** Почему запись с микрофона не дала файла. */
enum class MicProblem { UNAVAILABLE, EMPTY, NO_SPACE }

/** Запись с микрофона. */
@Immutable
sealed interface RecordingUi {
    /** Запись не идёт. */
    data object Idle : RecordingUi

    /** Служба выходит на передний план, микрофон ещё не открыт. */
    data object Starting : RecordingUi

    /** Идёт запись. [micBusy] означает, что микрофон у другого приложения и запись ждёт его. */
    data class Active(val elapsedMs: Long, val micBusy: Boolean) : RecordingUi

    /** Файл передан в конвейер как задача [jobId]. */
    data class Saved(val jobId: String) : RecordingUi

    /** Запись не удалась, и сохранять нечего. */
    data class Failed(val problem: MicProblem) : RecordingUi
}
