package ru.stenokot.app.data

import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.Stage
import ru.stenokot.core.protocol.ProtocolDocument
import ru.stenokot.core.storage.ServiceJson
import java.io.File

/** Пути внутри каталога задачи. Состав описан в разделе хранения спецификации. */
class JobFiles(val dir: File) {
    val job: File get() = File(dir, "job.json")
    val pcm: File get() = File(dir, "pcm16k.wav")
    val vad: File get() = File(dir, "vad.json")
    val lang: File get() = File(dir, "lang.json")
    val asr: File get() = File(dir, "asr.jsonl")
    val asrAttempt: File get() = File(dir, "asr.attempt")
    val diar: File get() = File(dir, "diar.jsonl")
    val diarAttempt: File get() = File(dir, "diar.attempt")
    val turns: File get() = File(dir, "turns.json")
    val merged: File get() = File(dir, "merged.json")
    val transcript: File get() = File(dir, "transcript.json")
    val protocol: File get() = File(dir, "protocol.json")
    val metadata: File get() = File(dir, "metadata.json")
    val metrics: File get() = File(dir, "metrics.jsonl")
    val llmStats: File get() = File(dir, "llm_stats.json")

    /** Копия исходного файла с расширением [extension]. */
    fun source(extension: String): File =
        File(dir, if (extension.isEmpty()) "source" else "source.$extension")
}

/**
 * Хранилище задач: `filesDir/jobs/<uuid>/`.
 *
 * Каталог передаётся функцией, потому что само обращение к filesDir создаёт каталог на диске,
 * а объект собирается на главном потоке. Методы блокирующие и вызываются только с фоновых потоков.
 */
class JobStore(private val root: () -> File) {
    /** Возвращает пути файлов задачи [id]. */
    fun files(id: String): JobFiles = JobFiles(File(root(), id))

    /** Атомарно заменяет job.json. */
    fun save(record: JobRecord) {
        AtomicFiles.replaceText(files(record.id).job, ServiceJson.encodeToString(JobRecord.serializer(), record))
    }

    /** Читает все задачи в порядке очереди. Каталог с нечитаемым job.json пропускается. */
    fun loadAll(): List<JobRecord> {
        val dirs = root().listFiles { file -> file.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir -> read(JobFiles(dir).job) }.sortedBy { it.seq }
    }

    /** Убирает недописанные файлы умершего процесса: продолжать их нельзя, а место они занимают. */
    fun dropPartial(id: String) {
        files(id).dir.listFiles { file -> PARTIAL_SUFFIXES.any(file.name::endsWith) }?.forEach(File::delete)
    }

    /** Удаляет каталог задачи вместе со всеми файлами. */
    fun delete(id: String) {
        files(id).dir.deleteRecursively()
    }

    /** Читает реплики задачи. Отсутствие файла даёт пустой список. */
    fun readUtterances(id: String): List<UtteranceLine> {
        val file = files(id).merged
        if (!file.exists()) return emptyList()
        return ServiceJson.decodeFromString(MergedDocument.serializer(), file.readText()).utterances
    }

    /** Читает протокол задачи, если он есть и читается. */
    fun readProtocol(id: String): ProtocolDocument? {
        val file = files(id).protocol
        if (!file.exists()) return null
        return runCatching { ServiceJson.decodeFromString(ProtocolDocument.serializer(), file.readText()) }.getOrNull()
    }

    private fun read(file: File): JobRecord? =
        runCatching { ServiceJson.decodeFromString(JobRecord.serializer(), file.readText()) }.getOrNull()

    private companion object {
        val PARTIAL_SUFFIXES = listOf(".tmp", ".part")
    }
}

/**
 * Сверяет запись задачи с файлами на диске после смерти процесса.
 *
 * Стадия считается завершённой, только если её результат существует и существуют результаты всех
 * стадий до неё. Обрыв обработки показывается как прерывание с кнопкой «Продолжить». Если же
 * восстановить результат не из чего, задача честно получает отметку о потере результата.
 *
 * Очередь прошлого запуска тоже становится прерванной: иначе она встала бы впереди файла, ради
 * которого приложение открыли заново.
 */
object JobReconciler {
    /**
     * Возвращает запись, приведённую к файлам. [exitCode] описывает причину смерти процесса,
     * [stoppedByUser] означает, что приложение закрыли принудительно: такое решение за человека
     * не отменяется, и продолжить задачу можно только нажатием.
     */
    fun reconcile(
        record: JobRecord,
        files: JobFiles,
        exitCode: ErrorCode? = null,
        stoppedByUser: Boolean = false,
    ): JobRecord {
        val settled = settle(record, files, exitCode)
        return if (stoppedByUser && settled.phase == JobPhase.INTERRUPTED) settled.copy(manualResume = true) else settled
    }

    private fun settle(record: JobRecord, files: JobFiles, exitCode: ErrorCode?): JobRecord {
        val confirmed = confirmed(record, files)
        val first = CoreStages.firstOrNull { it !in confirmed }
        val synced = record.copy(completedStages = confirmed)
        return when (record.phase) {
            JobPhase.CANCELLED, JobPhase.FAILED -> synced
            JobPhase.QUEUED -> interrupted(synced, first, exitCode)
            JobPhase.INTERRUPTED -> interrupted(synced, first, record.errorCode)
            JobPhase.RUNNING, JobPhase.PAUSED -> {
                if (record.stage == Stage.LLM || first == null) {
                    afterTranscript(synced, first, exitCode)
                } else {
                    interrupted(synced.copy(deaths = record.deaths + 1), first, exitCode)
                }
            }
            JobPhase.TRANSCRIPT_READY, JobPhase.WAITING_MODEL -> afterTranscript(synced, first, exitCode)
            JobPhase.DONE -> if (first == null) synced else lost(synced, first, exitCode)
        }
    }

    private fun afterTranscript(
        record: JobRecord,
        first: Stage?,
        exitCode: ErrorCode?,
    ): JobRecord {
        if (first != null) return lost(record, first, exitCode)
        val llm = record.llm.copy(status = LlmStatus.DEFERRED)
        return record.copy(phase = JobPhase.DONE, stage = Stage.LLM, llm = llm, pauseReason = null)
    }

    private fun lost(
        record: JobRecord,
        first: Stage,
        exitCode: ErrorCode?,
    ): JobRecord =
        if (Stage.IMPORT in record.completedStages) {
            interrupted(record, first, exitCode)
        } else {
            record.copy(
                phase = JobPhase.FAILED,
                stage = first,
                errorCode = ErrorCode.IMPORT_READ_FAILED,
                errorDetail = RESULT_LOST_DETAIL,
            )
        }

    private fun interrupted(
        record: JobRecord,
        first: Stage?,
        code: ErrorCode?,
    ): JobRecord = record.copy(
        phase = JobPhase.INTERRUPTED,
        stage = first ?: record.stage,
        errorCode = code,
        pauseReason = null,
    )

    private fun confirmed(record: JobRecord, files: JobFiles): Set<Stage> {
        val confirmed = LinkedHashSet<Stage>()
        for (stage in CoreStages) {
            if (stage !in record.completedStages) break
            if (!artifactExists(stage, record, files)) break
            confirmed += stage
        }
        return confirmed
    }

    private fun artifactExists(stage: Stage, record: JobRecord, files: JobFiles): Boolean = when (stage) {
        Stage.IMPORT -> record.sourceInPlace || (record.sourceFile != null && File(files.dir, record.sourceFile).exists())
        Stage.DECODE -> files.pcm.exists()
        Stage.VAD -> files.vad.exists()
        Stage.LANG_ID -> record.noSpeech || files.lang.exists()
        Stage.ASR -> record.noSpeech || files.asr.exists()
        Stage.DIARIZE -> record.noSpeech || files.turns.exists()
        Stage.MERGE -> files.merged.exists()
        Stage.VALIDATE -> files.transcript.exists()
        Stage.LLM -> true
    }
}
