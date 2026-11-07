package ru.stenokot.app.pipeline

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.stenokot.app.data.JobRecord
import ru.stenokot.app.data.toState
import ru.stenokot.core.model.statusCode
import ru.stenokot.core.storage.ModelHash
import ru.stenokot.core.storage.ServiceJson

/** Задача в диагностическом пакете. Имени файла, путей и текста реплик здесь нет. */
@Serializable
data class DiagnosticJob(
    val id: String,
    val status: String,
    val stage: String,
    @SerialName("error_code") val errorCode: String?,
    @SerialName("error_detail") val errorDetail: String?,
    @SerialName("completed_stages") val completedStages: List<String>,
    @SerialName("stage_ms") val stageMs: Map<String, Long>,
    @SerialName("audio_duration_ms") val audioDurationMs: Long?,
    @SerialName("source_mime") val sourceMime: String?,
    @SerialName("source_sample_rate") val sourceSampleRate: Int?,
    @SerialName("rtf_core") val rtfCore: Double?,
    @SerialName("rtf_full") val rtfFull: Double?,
    @SerialName("vm_hwm_kb") val vmHwmKb: Long?,
    @SerialName("transcript_sha256") val transcriptSha256: String?,
    @SerialName("export_failed") val exportFailed: Boolean?,
    @SerialName("llm_status") val llmStatus: String?,
    @SerialName("llm_error") val llmError: String?,
    @SerialName("llm_retry_used") val llmRetryUsed: Boolean,
    val warnings: List<String>,
    @SerialName("skipped_segments") val skippedSegments: List<Int>,
    @SerialName("skipped_windows") val skippedWindows: List<Int>,
)

/** Диагностический пакет: всё для разбора сбоя, но ни звука, ни текста реплик. */
@Serializable
data class DiagnosticPackage(
    @SerialName("app_version") val appVersion: String,
    val commit: String,
    val device: String,
    @SerialName("api_level") val apiLevel: Int,
    @SerialName("total_mem_bytes") val totalMemBytes: Long,
    @SerialName("internet_permission_absent") val internetAbsent: Boolean,
    @SerialName("network_state_permission_absent") val networkStateAbsent: Boolean,
    val models: List<ModelHash>,
    val jobs: List<DiagnosticJob>,
)

/** Собирает диагностический пакет из значений в памяти. */
object Diagnostics {
    private val PrettyJson = Json(ServiceJson) { prettyPrint = true }

    /** Описывает задачу. Имя файла вырезается и из подробности ошибки. */
    fun job(job: JobRecord): DiagnosticJob {
        val rtf = job.rtf()
        return DiagnosticJob(
            id = job.id,
            status = statusCode(job.toState()),
            stage = job.stage.name,
            errorCode = job.errorCode?.name,
            errorDetail = job.errorDetail?.let { redact(it, job) },
            completedStages = job.completedStages.map { it.name },
            stageMs = job.stageMs.mapKeys { it.key.name },
            audioDurationMs = job.durationMs,
            sourceMime = job.audio?.mime ?: job.preflight?.mime,
            sourceSampleRate = job.audio?.sourceSampleRate,
            rtfCore = rtf.core,
            rtfFull = rtf.full,
            vmHwmKb = job.peakRssKb,
            transcriptSha256 = job.output?.sha256,
            exportFailed = job.output?.exportFailed,
            llmStatus = job.llm.status?.name,
            llmError = job.llm.errorCode?.name,
            llmRetryUsed = job.llm.retryUsed,
            warnings = job.warnings,
            skippedSegments = job.skippedSegments,
            skippedWindows = job.skippedWindows,
        )
    }

    /** Переводит пакет в текст. */
    fun encode(value: DiagnosticPackage): String =
        PrettyJson.encodeToString(DiagnosticPackage.serializer(), value)

    private fun redact(detail: String, job: JobRecord): String =
        detail.replace(job.title, "<файл>").replace(job.stem, "<файл>")
}
