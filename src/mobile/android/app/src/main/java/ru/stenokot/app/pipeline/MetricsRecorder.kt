package ru.stenokot.app.pipeline

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.stenokot.app.data.AtomicFiles
import ru.stenokot.app.data.JobFiles
import ru.stenokot.app.data.JobRecord
import ru.stenokot.app.data.LineJournal
import ru.stenokot.core.metrics.RtfCalculator
import ru.stenokot.core.metrics.RtfValues
import ru.stenokot.core.model.Stage
import ru.stenokot.core.storage.LlmMetadata
import ru.stenokot.core.storage.MetadataDocument
import ru.stenokot.core.storage.ModelHash
import ru.stenokot.core.storage.ServiceJson
import ru.stenokot.core.storage.StageTiming
import ru.stenokot.core.storage.ThermalEvent

/** Замер одной стадии, строка metrics.jsonl. */
@Serializable
data class StageMetrics(
    val stage: Stage,
    @SerialName("started_at_ms") val startedAtMs: Long,
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
    val device: String,
    val apiLevel: Int,
    val totalMemBytes: Long,
    val threads: Int,
)

/** Токены языковой модели. Файл llm_stats.json пишет процесс модели, лишние поля пропускаются. */
@Serializable
data class LlmStats(
    @SerialName("prompt_tokens") val promptTokens: Long? = null,
    @SerialName("completion_tokens") val completionTokens: Long? = null,
    @SerialName("windows_done") val windowsDone: Int? = null,
    @SerialName("windows_total") val windowsTotal: Int? = null,
    @SerialName("vm_hwm_kb") val vmHwmKb: Long? = null,
)

/** Значения `RTF_core` и `RTF_full` задачи. Слово «RTF» без уточнения в продукте не используется. */
fun JobRecord.rtf(): RtfValues {
    val audioMs = durationMs ?: 0
    val timed = if (llm.status == null) stageMs else stageMs + (Stage.LLM to llm.tookMs)
    return RtfCalculator.compute(audioMs, timed, overheadMs)
}

/** Пишет замеры стадий и сводный metadata.json в формате ядра. */
class MetricsRecorder(
    private val context: () -> RunContext,
    private val modelHashes: () -> List<ModelHash>,
) {
    /** Дописывает замер стадии в metrics.jsonl. */
    fun stage(files: JobFiles, metrics: StageMetrics) {
        LineJournal(files.metrics).append(ServiceJson.encodeToString(StageMetrics.serializer(), metrics))
    }

    /** Читает замеры стадий задачи. */
    fun readStages(files: JobFiles): List<StageMetrics> = LineJournal(files.metrics).restore { line ->
        runCatching { ServiceJson.decodeFromString(StageMetrics.serializer(), line) }.getOrNull()
    }

    /** Читает токены языковой модели, если её процесс их записал. */
    fun llmStats(files: JobFiles): LlmStats? {
        if (!files.llmStats.exists()) return null
        return runCatching {
            ServiceJson.decodeFromString(LlmStats.serializer(), files.llmStats.readText())
        }.getOrNull()
    }

    /** Атомарно заменяет metadata.json текущими значениями задачи. */
    fun writeMetadata(job: JobRecord, files: JobFiles) {
        val text = MetadataJson.encodeToString(MetadataDocument.serializer(), metadata(job, files))
        AtomicFiles.replaceText(files.metadata, text)
    }

    /** Строит содержимое metadata.json. Текста реплик и аудио в нём нет. */
    fun metadata(job: JobRecord, files: JobFiles): MetadataDocument {
        val run = context()
        val rtf = job.rtf()
        val stages = readStages(files)
        val stats = llmStats(files)
        return MetadataDocument(
            appVersion = run.appVersion,
            commit = run.commit,
            models = modelHashes(),
            device = run.device,
            apiLevel = run.apiLevel,
            totalMemBytes = run.totalMemBytes,
            threads = run.threads,
            audioDurationMs = job.durationMs,
            sourceSha256 = job.audio?.sourceSha256,
            language = job.language,
            langVotes = job.langVotes,
            stages = job.stageMs.map { (stage, ms) ->
                StageTiming(stage, ms, job.durationMs?.let { RtfCalculator.stage(ms, it) })
            },
            overheadMs = job.overheadMs,
            rtfCore = rtf.core,
            rtfFull = rtf.full,
            vmHwmKb = listOfNotNull(job.peakRssKb, stats?.vmHwmKb).maxOrNull(),
            thermal = thermalEvents(stages),
            llm = LlmMetadata(
                status = job.llm.status,
                model = LLM_MODEL_NAME,
                promptTokens = job.llm.promptTokens,
                completionTokens = job.llm.completionTokens,
                windowsDone = stats?.windowsDone,
                windowsTotal = stats?.windowsTotal,
                timeMs = job.llm.tookMs.takeIf { job.llm.status != null },
                retryUsed = job.llm.retryUsed,
                vmHwmKb = stats?.vmHwmKb,
            ),
            speakers = job.speakers,
            utterances = job.utterances,
            transcriptSha256 = job.output?.sha256,
            transcriptValid = job.output?.validated,
            transcriptPath = job.output?.path,
            warnings = warnings(job),
        )
    }

    private fun thermalEvents(stages: List<StageMetrics>): List<ThermalEvent> {
        val events = ArrayList<ThermalEvent>()
        for (stage in stages) {
            if (events.lastOrNull()?.status != stage.thermalStart) {
                events += ThermalEvent(stage.startedAtMs, stage.thermalStart)
            }
            if (stage.thermalEnd != stage.thermalStart) {
                events += ThermalEvent(stage.startedAtMs + stage.tookMs, stage.thermalEnd)
            }
        }
        return events
    }

    private fun warnings(job: JobRecord): List<String> =
        job.warnings +
            job.skippedSegments.map { "SKIPPED_SEGMENT:$it" } +
            job.skippedWindows.map { "SKIPPED_WINDOW:$it" }

    private companion object {
        const val LLM_MODEL_NAME = "Qwen3-0.6B-Q4_K_M"
        val MetadataJson = Json(ServiceJson) { prettyPrint = true }
    }
}
