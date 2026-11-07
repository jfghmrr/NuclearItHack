package ru.stenokot.core.storage

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.AsrToken
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.SpeechSegment
import ru.stenokot.core.model.Stage

/** Настройки служебных файлов. Неизвестные поля не мешают открыть файл другой версии приложения. */
val ServiceJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Содержимое job.json: состояние задачи для продолжения после перезапуска. Стадия считается
 * пройденной, только если её результат уже записан на диск. Смена настроек или моделей отменяет
 * всё после декодирования: копия файла и pcm16k.wav от них не зависят, журналы приходится начать
 * заново.
 */
@Serializable
data class JobFile(
    val id: String,
    @SerialName("source_name") val sourceName: String,
    @SerialName("source_ext") val sourceExt: String,
    val stem: String,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("config_hash") val configHash: String,
    @SerialName("models_hash") val modelsHash: String,
    @SerialName("completed_stages") val completedStages: List<Stage> = emptyList(),
    @SerialName("stage_ms") val stageMs: Map<Stage, Long> = emptyMap(),
    @SerialName("current_stage") val currentStage: Stage? = null,
    @SerialName("asr_chunks_done") val asrChunksDone: Int = 0,
    @SerialName("asr_chunks_total") val asrChunksTotal: Int? = null,
    @SerialName("diar_windows_done") val diarWindowsDone: Int = 0,
    @SerialName("diar_windows_total") val diarWindowsTotal: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("sample_rate") val sampleRate: Int? = null,
    val channels: Int? = null,
    val mime: String? = null,
    @SerialName("source_sha256") val sourceSha256: String? = null,
    val language: Language? = null,
    @SerialName("transcript_path") val transcriptPath: String? = null,
    @SerialName("error_code") val errorCode: ErrorCode? = null,
    @SerialName("error_detail") val errorDetail: String? = null,
    val cancelled: Boolean = false,
    @SerialName("llm_status") val llmStatus: LlmStatus? = null,
    @SerialName("llm_retry_used") val llmRetryUsed: Boolean = false,
) {
    /** Первая стадия, которую ещё нужно выполнить. Значение null означает, что всё пройдено. */
    fun nextStage(): Stage? = Stage.entries.firstOrNull { it !in completedStages }

    /** Отмечает стадию пройденной и запоминает время её работы. */
    fun withCompleted(stage: Stage, tookMs: Long): JobFile =
        copy(
            completedStages = (completedStages + stage).distinct(),
            stageMs = stageMs + (stage to tookMs),
            currentStage = null,
        )

    /** Отменяет пройденные стадии при смене настроек или моделей. */
    fun invalidatedFor(newConfigHash: String, newModelsHash: String): JobFile =
        if (newConfigHash == configHash && newModelsHash == modelsHash) {
            this
        } else {
            val kept = completedStages.filter { it <= Stage.DECODE }
            copy(
                configHash = newConfigHash,
                modelsHash = newModelsHash,
                completedStages = kept,
                stageMs = stageMs.filterKeys { it in kept },
                asrChunksDone = 0,
                diarWindowsDone = 0,
            )
        }
}

/** Строка asr.jsonl: один завершённый срез. Токены и их начало лежат параллельными массивами. */
@Serializable
data class AsrJournalLine(
    val index: Int,
    @SerialName("start_sample") val startSample: Long,
    @SerialName("end_sample") val endSample: Long,
    val text: String,
    val tokens: List<String> = emptyList(),
    @SerialName("starts_ms") val startsMs: List<Long> = emptyList(),
    val skipped: Boolean = false,
) {
    /** Восстанавливает результат распознавания. */
    fun toSegment(): AsrSegment =
        AsrSegment(
            SpeechSegment(index, startSample, endSample),
            text,
            tokens.indices.map { AsrToken(tokens[it], startsMs[it]) },
        )

    companion object {
        /** Готовит строку журнала из результата распознавания. */
        fun of(segment: AsrSegment): AsrJournalLine =
            AsrJournalLine(
                index = segment.segment.index,
                startSample = segment.segment.startSample,
                endSample = segment.segment.endSample,
                text = segment.text,
                tokens = segment.tokens.map { it.text },
                startsMs = segment.tokens.map { it.startMs },
            )
    }
}

/** Интервал речи кластера окна диаризации на шкале записи. */
@Serializable
data class DiarTurnRecord(
    val cluster: Int,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
)

/** Кластер окна диаризации. Отпечатка голоса нет, если у кластера слишком мало чистой речи. */
@Serializable
data class DiarClusterRecord(
    val cluster: Int,
    @SerialName("speech_ms") val speechMs: Long,
    val embedding: List<Float>? = null,
)

/** Строка diar.jsonl: одно завершённое окно. Этих данных достаточно, чтобы собрать книгу заново. */
@Serializable
data class DiarJournalLine(
    val index: Int,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val turns: List<DiarTurnRecord>,
    val clusters: List<DiarClusterRecord>,
)

/** Хэш и размер файла модели. */
@Serializable
data class ModelHash(val name: String, val sha256: String, @SerialName("size_bytes") val sizeBytes: Long? = null)

/** Голоса определения языка. */
@Serializable
data class LanguageVotes(val ru: Int, val en: Int, val chunks: Int)

/** Время и скорость одной стадии. */
@Serializable
data class StageTiming(val stage: Stage, val ms: Long, val rtf: Double? = null)

/** Запись о смене теплового состояния устройства. */
@Serializable
data class ThermalEvent(@SerialName("at_ms") val atMs: Long, val status: Int)

/** Сведения о работе языковой модели. */
@Serializable
data class LlmMetadata(
    val status: LlmStatus? = null,
    val model: String? = null,
    @SerialName("prompt_tokens") val promptTokens: Long? = null,
    @SerialName("completion_tokens") val completionTokens: Long? = null,
    @SerialName("windows_done") val windowsDone: Int? = null,
    @SerialName("windows_total") val windowsTotal: Int? = null,
    @SerialName("time_ms") val timeMs: Long? = null,
    @SerialName("retry_used") val retryUsed: Boolean = false,
)

/** Содержимое metadata.json: доказательства прогона. Текст реплик и аудио сюда не попадают. */
@Serializable
data class MetadataDocument(
    @SerialName("app_version") val appVersion: String,
    val commit: String? = null,
    val models: List<ModelHash> = emptyList(),
    val device: String? = null,
    @SerialName("api_level") val apiLevel: Int? = null,
    @SerialName("total_mem_bytes") val totalMemBytes: Long? = null,
    val threads: Int? = null,
    @SerialName("audio_duration_ms") val audioDurationMs: Long? = null,
    @SerialName("source_sha256") val sourceSha256: String? = null,
    val language: Language? = null,
    @SerialName("lang_votes") val langVotes: LanguageVotes? = null,
    val stages: List<StageTiming> = emptyList(),
    @SerialName("rtf_core") val rtfCore: Double? = null,
    @SerialName("rtf_full") val rtfFull: Double? = null,
    @SerialName("vm_hwm_kb") val vmHwmKb: Long? = null,
    val thermal: List<ThermalEvent> = emptyList(),
    val llm: LlmMetadata = LlmMetadata(),
    val speakers: Int? = null,
    val utterances: Int? = null,
    @SerialName("transcript_sha256") val transcriptSha256: String? = null,
    @SerialName("transcript_valid") val transcriptValid: Boolean? = null,
    @SerialName("transcript_path") val transcriptPath: String? = null,
    val warnings: List<String> = emptyList(),
)

/** Записывает значение одной строкой журнала вместе с переводом строки. */
fun <T> encodeJournalLine(serializer: KSerializer<T>, value: T): String =
    ServiceJson.encodeToString(serializer, value) + "\n"

/**
 * Читает журнал до первой испорченной строки. Хвост мог оборваться при убийстве процесса,
 * поэтому всё после разрыва считается не выполненным и будет пересчитано.
 */
fun <T> decodeJournal(serializer: KSerializer<T>, text: String): List<T> {
    val out = ArrayList<T>()
    for (line in text.lineSequence()) {
        if (line.isBlank()) continue
        try {
            out += ServiceJson.decodeFromString(serializer, line)
        } catch (_: SerializationException) {
            return out
        } catch (_: IllegalArgumentException) {
            return out
        }
    }
    return out
}
