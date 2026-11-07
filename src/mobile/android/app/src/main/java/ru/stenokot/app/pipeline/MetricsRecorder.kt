package ru.stenokot.app.pipeline

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ru.stenokot.app.data.AtomicFiles
import ru.stenokot.app.data.JobFiles
import ru.stenokot.app.data.JobRecord
import ru.stenokot.app.data.LineJournal
import ru.stenokot.app.data.StoreJson
import ru.stenokot.core.model.Stage

/** Замер одной стадии, строка metrics.jsonl. */
@Serializable
data class StageMetrics(
    val stage: Stage,
    @SerialName("took_ms") val tookMs: Long,
    val rtf: Double? = null,
    @SerialName("avail_mem_mb") val availMemMb: Long,
    @SerialName("thermal_start") val thermalStart: Int,
    @SerialName("thermal_end") val thermalEnd: Int,
    @SerialName("vm_hwm_kb") val vmHwmKb: Long? = null,
)

/** Неизменные сведения о сборке и устройстве для metadata.json. */
data class RunContext(
    val appVersion: String,
    val commit: String,
    val deviceModel: String,
    val apiLevel: Int,
    val totalMemBytes: Long,
    val threads: Int,
)

/** Токены языковой модели. Файл llm_stats.json пишет процесс модели. */
@Serializable
data class LlmStats(
    @SerialName("prompt_tokens") val promptTokens: Long? = null,
    @SerialName("completion_tokens") val completionTokens: Long? = null,
)

/** Раздел языковой модели в metadata.json. */
@Serializable
data class LlmMetadata(
    val status: String?,
    @SerialName("retry_used") val retryUsed: Boolean,
    @SerialName("error_code") val errorCode: String?,
    @SerialName("took_ms") val tookMs: Long,
    @SerialName("prompt_tokens") val promptTokens: Long?,
    @SerialName("completion_tokens") val completionTokens: Long?,
)

/** Содержимое metadata.json. Текста реплик и аудио здесь нет. */
@Serializable
data class MetadataDocument(
    @SerialName("app_version") val appVersion: String,
    val commit: String,
    val models: Map<String, String>,
    @SerialName("device_model") val deviceModel: String,
    @SerialName("api_level") val apiLevel: Int,
    @SerialName("total_mem_mb") val totalMemMb: Long,
    val threads: Int,
    val language: String?,
    @SerialName("lang_id_failed") val langIdFailed: Boolean,
    @SerialName("audio_duration_ms") val audioDurationMs: Long?,
    @SerialName("source_sha256") val sourceSha256: String?,
    @SerialName("transcript_sha256") val transcriptSha256: String?,
    val stages: List<StageMetrics>,
    @SerialName("core_ms") val coreMs: Long,
    @SerialName("rtf_core") val rtfCore: Double?,
    @SerialName("rtf_full") val rtfFull: Double?,
    @SerialName("vm_hwm_kb") val vmHwmKb: Long?,
    val llm: LlmMetadata,
    val warnings: List<String>,
    @SerialName("skipped_segments") val skippedSegments: List<Int>,
    @SerialName("skipped_windows") val skippedWindows: List<Int>,
)

/** Расчёт RTF. Слово «RTF» без уточнения в продукте не используется. */
object Rtf {
    /** Отношение времени работы к длительности записи или null, если длительность неизвестна. */
    fun of(tookMs: Long, durationMs: Long?): Double? =
        durationMs?.takeIf { it > 0 }?.let { tookMs.toDouble() / it }

    /** `RTF_core`: всё до записи JSON включительно. */
    fun core(job: JobRecord): Double? = of(job.coreMs, job.durationMs)

    /** `RTF_full`: то же плюс языковая модель. До её завершения значения нет. */
    fun full(job: JobRecord): Double? =
        if (job.llm.status == null) null else of(job.coreMs + job.llm.tookMs, job.durationMs)
}

/** Пишет замеры стадий и сводный metadata.json. */
class MetricsRecorder(
    private val context: () -> RunContext,
    private val modelHashes: () -> Map<String, String>,
) {
    /** Дописывает замер стадии в metrics.jsonl. */
    fun stage(files: JobFiles, metrics: StageMetrics) {
        LineJournal(files.metrics).append(StoreJson.encodeToString(metrics))
    }

    /** Читает токены языковой модели, если процесс модели их записал. */
    fun llmStats(files: JobFiles): LlmStats? {
        if (!files.llmStats.exists()) return null
        return runCatching { StoreJson.decodeFromString<LlmStats>(files.llmStats.readText()) }.getOrNull()
    }

    /** Атомарно заменяет metadata.json текущими значениями задачи. */
    fun writeMetadata(job: JobRecord, files: JobFiles) {
        val document = metadata(job, files)
        AtomicFiles.replaceText(files.metadata, MetadataJson.encodeToString(document))
    }

    /** Строит содержимое metadata.json. */
    fun metadata(job: JobRecord, files: JobFiles): MetadataDocument {
        val run = context()
        return MetadataDocument(
            appVersion = run.appVersion,
            commit = run.commit,
            models = modelHashes(),
            deviceModel = run.deviceModel,
            apiLevel = run.apiLevel,
            totalMemMb = run.totalMemBytes / BYTES_IN_MB,
            threads = run.threads,
            language = job.language?.name,
            langIdFailed = job.langFailed,
            audioDurationMs = job.durationMs,
            sourceSha256 = job.audio?.sourceSha256,
            transcriptSha256 = job.output?.sha256,
            stages = readStages(files),
            coreMs = job.coreMs,
            rtfCore = Rtf.core(job),
            rtfFull = Rtf.full(job),
            vmHwmKb = job.peakRssKb,
            llm = LlmMetadata(
                status = job.llm.status?.name,
                retryUsed = job.llm.retryUsed,
                errorCode = job.llm.errorCode?.name,
                tookMs = job.llm.tookMs,
                promptTokens = job.llm.promptTokens,
                completionTokens = job.llm.completionTokens,
            ),
            warnings = job.warnings,
            skippedSegments = job.skippedSegments,
            skippedWindows = job.skippedWindows,
        )
    }

    /** Читает замеры стадий задачи. */
    fun readStages(files: JobFiles): List<StageMetrics> = LineJournal(files.metrics).restore { line ->
        runCatching { StoreJson.decodeFromString<StageMetrics>(line) }.getOrNull()
    }

    private companion object {
        const val BYTES_IN_MB = 1_000_000L
    }
}

private val MetadataJson = kotlinx.serialization.json.Json {
    prettyPrint = true
    encodeDefaults = true
}
