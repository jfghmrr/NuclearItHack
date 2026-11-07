package ru.stenokot.app.pipeline

import ru.stenokot.app.data.JobPhase
import ru.stenokot.app.data.JobRecord
import ru.stenokot.app.data.toState
import ru.stenokot.app.domain.JobSummaryUi
import ru.stenokot.app.domain.ModelsUi
import ru.stenokot.app.domain.OutputFileUi
import ru.stenokot.app.domain.ProcessingUi
import ru.stenokot.app.domain.ProtocolUi
import ru.stenokot.app.domain.ResultUi
import ru.stenokot.app.domain.StageMetricUi
import ru.stenokot.app.domain.StageStatus
import ru.stenokot.app.domain.StageUi
import ru.stenokot.app.domain.UserStage
import ru.stenokot.core.metrics.DeviceSpeed
import ru.stenokot.core.metrics.EtaEstimator
import ru.stenokot.core.metrics.EtaProgress
import ru.stenokot.core.metrics.RtfCalculator
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.Stage
import ru.stenokot.core.model.Utterance
import ru.stenokot.core.model.statusCode
import ru.stenokot.core.protocol.ProtocolDocument

/** Переводит записи задач в модели интерфейса. Чистые функции без ввода-вывода. */
object JobUiMapper {
    private const val BYTES_IN_KIB = 1024
    private const val MS_IN_SECOND = 1000

    /** Состояние задачи с учётом живого прогресса. */
    fun state(job: JobRecord, live: LiveProgress?): JobState =
        job.toState(live?.takeIf { it.jobId == job.id && it.stage == job.stage }?.fraction ?: 0f)

    /** Строка очереди и истории. */
    fun summary(job: JobRecord, live: LiveProgress?, all: Collection<JobRecord>): JobSummaryUi = JobSummaryUi(
        id = job.id,
        title = job.title,
        createdAtMs = job.createdAtMs,
        durationMs = job.durationMs,
        speakers = job.speakers,
        state = state(job, live),
        queuePosition = JobQueue.position(all, job.id),
    )

    /**
     * Машинная строка главного экрана. Задача в работе важнее всего. Иначе показывается итог
     * последней задачи этого запуска, а старая история экран не занимает: при свежем открытии там
     * IDLE. Прерванная задача видна всегда, потому что она ждёт действия пользователя.
     */
    fun homeStatus(all: Collection<JobRecord>, live: LiveProgress?, session: Set<String>): String {
        val active = all.firstOrNull(JobQueue::isActive)
        if (active != null) return statusCode(state(active, live))
        val waiting = JobQueue.next(all)
        if (waiting != null) return statusCode(state(waiting, live))
        val interrupted = all.filter { it.phase == JobPhase.INTERRUPTED }.maxByOrNull { it.seq }
        if (interrupted != null) return statusCode(state(interrupted, live))
        val recent = all.filter { it.id in session }.maxByOrNull { it.seq } ?: return statusCode(null)
        return statusCode(state(recent, live))
    }

    /** Экран обработки. */
    fun processing(
        job: JobRecord,
        live: LiveProgress?,
        speed: DeviceSpeed?,
        powerSave: Boolean = false,
    ): ProcessingUi {
        val own = live?.takeIf { it.jobId == job.id }
        val remaining = own?.let { remaining(job, it, speed) }
        return ProcessingUi(
            preflight = job.preflight?.toInfo(),
            stages = UserStage.entries.map { stage(it, job, own) },
            elapsedMs = own?.elapsedMs ?: job.stageMs.filterKeys { it != Stage.LLM }.values.sum(),
            remainingMinMs = remaining?.first,
            remainingMaxMs = remaining?.second,
            lowMemory = own?.lowMemory ?: false,
            cancelling = own?.cancelling ?: false,
            powerSave = powerSave,
            resumed = job.resumed,
        )
    }

    private fun remaining(job: JobRecord, live: LiveProgress, speed: DeviceSpeed?): Pair<Long, Long>? {
        val audioMs = job.durationMs ?: return null
        val progress = EtaProgress(
            audioMs = audioMs,
            stage = live.stage,
            stageFraction = live.fraction,
            stageElapsedMs = live.stageElapsedMs,
            finishedStageMs = job.stageMs.filterKeys { it in job.completedStages },
        )
        return EtaEstimator(speed).remaining(progress)?.let { it.minMs to it.maxMs }
    }

    private fun stage(stage: UserStage, job: JobRecord, live: LiveProgress?): StageUi {
        val status = status(stage, job)
        val active = status == StageStatus.ACTIVE
        val counters = if (active) counters(job, live) else null
        return StageUi(
            stage = stage,
            status = status,
            fraction = if (active) fraction(stage, job, live) else null,
            counterDone = counters?.first,
            counterTotal = counters?.second,
            tookMs = took(stage, job, live),
        )
    }

    private fun status(stage: UserStage, job: JobRecord): StageStatus {
        if (stage == UserStage.BUILD_PROTOCOL) return protocolStatus(job)
        if (job.noSpeech && (stage == UserStage.RECOGNIZE || stage == UserStage.DIARIZE)) return StageStatus.SKIPPED
        if (job.completedStages.containsAll(stage.stages)) return StageStatus.DONE
        if (job.stage !in stage.stages) return StageStatus.PENDING
        return when (job.phase) {
            JobPhase.RUNNING, JobPhase.PAUSED -> StageStatus.ACTIVE
            JobPhase.FAILED -> StageStatus.FAILED
            else -> StageStatus.PENDING
        }
    }

    private fun protocolStatus(job: JobRecord): StageStatus {
        if (job.noSpeech) return StageStatus.SKIPPED
        return when (job.phase) {
            JobPhase.TRANSCRIPT_READY, JobPhase.WAITING_MODEL -> StageStatus.ACTIVE
            JobPhase.RUNNING -> if (job.stage == Stage.LLM) StageStatus.ACTIVE else StageStatus.PENDING
            JobPhase.DONE -> when (job.llm.status) {
                LlmStatus.APPLIED, LlmStatus.PARTIAL -> StageStatus.DONE
                LlmStatus.FAILED -> StageStatus.FAILED
                else -> StageStatus.SKIPPED
            }
            else -> StageStatus.PENDING
        }
    }

    /** Этап из нескольких стадий копит долю по стадиям, а этап без замеров доли не показывает. */
    private fun fraction(stage: UserStage, job: JobRecord, live: LiveProgress?): Float? {
        if (stage == UserStage.SAVE_TRANSCRIPT) return null
        val parts = Stage.entries.filter { it in stage.stages }
        val finished = parts.count { it in job.completedStages }
        val current = live?.fraction ?: if (finished == 0) return null else 0f
        return (finished + current) / parts.size
    }

    /** Единицы счётчика зависят от стадии: КиБ, секунды записи, фрагменты или окна. */
    private fun counters(job: JobRecord, live: LiveProgress?): Pair<Int, Int>? {
        val own = live?.takeIf { it.stage == job.stage } ?: return null
        val done = own.counterDone
        val total = own.counterTotal
        if (done != null && total != null) return done to total
        val fraction = own.fraction ?: return null
        val whole = when (own.stage) {
            Stage.IMPORT -> job.preflight?.sizeBytes?.let { it / BYTES_IN_KIB }
            Stage.DECODE, Stage.VAD -> job.durationMs?.let { it / MS_IN_SECOND }
            else -> null
        }?.toInt() ?: return null
        return (whole * fraction).toInt() to whole
    }

    /** Время этапа: завершённые стадии и идущая, чтобы строка рельса не отставала от общего времени. */
    private fun took(stage: UserStage, job: JobRecord, live: LiveProgress?): Long? {
        if (stage == UserStage.BUILD_PROTOCOL) return job.llm.tookMs.takeIf { it > 0 }
        val finished = stage.stages.mapNotNull { job.stageMs[it] }
        val running = live?.takeIf { it.stage in stage.stages && it.stage !in job.completedStages }?.stageElapsedMs
        return (finished + listOfNotNull(running)).takeIf { it.isNotEmpty() }?.sum()
    }

    /** Сведения о контрактном файле. */
    fun output(job: JobRecord): OutputFileUi? = job.output?.let { output ->
        OutputFileUi(
            path = output.path,
            sizeBytes = output.sizeBytes,
            createdAtMs = output.createdAtMs,
            validated = output.validated,
            sha256 = output.sha256,
            exportFailed = output.exportFailed,
            contentUri = output.uri,
        )
    }

    /** Состояние протокола. Он никогда не влияет на готовность стенограммы. */
    fun protocol(
        job: JobRecord,
        live: LiveProgress?,
        document: ProtocolDocument?,
        models: ModelsUi?,
    ): ProtocolUi {
        val own = live?.takeIf { it.jobId == job.id && it.stage == Stage.LLM }
        val waiting = job.phase == JobPhase.WAITING_MODEL
        return ProtocolUi(
            status = job.llm.status,
            running = job.phase == JobPhase.RUNNING && job.stage == Stage.LLM,
            windowsDone = own?.counterDone ?: job.llm.windowsDone,
            windowsTotal = own?.counterTotal ?: job.llm.windowsTotal,
            modelCopiedBytes = models?.copiedBytes?.takeIf { waiting },
            modelTotalBytes = models?.totalBytes?.takeIf { waiting },
            retryUsed = job.llm.retryUsed,
            document = document,
            errorCode = job.llm.errorCode,
        )
    }

    /** Экран результата. Значение null означает, что контрактного файла ещё нет. */
    fun result(
        job: JobRecord,
        live: LiveProgress?,
        utterances: List<Utterance>,
        document: ProtocolDocument?,
        models: ModelsUi?,
        audioPath: String?,
    ): ResultUi? {
        val output = output(job) ?: return null
        return ResultUi(
            title = job.title,
            durationMs = job.durationMs ?: 0,
            language = job.language ?: Language.RU,
            speakers = job.speakers ?: 0,
            utterances = utterances,
            audioPath = audioPath,
            output = output,
            protocol = protocol(job, live, document, models),
        )
    }

    /** Замеры стадий для экрана проверки. Время языковой модели хранится отдельно от основных стадий. */
    fun stageMetrics(job: JobRecord): List<StageMetricUi> {
        val llm = job.llm.tookMs.takeIf { it > 0 }?.let { mapOf(Stage.LLM to it) }.orEmpty()
        return (job.stageMs + llm).entries
            .sortedBy { it.key }
            .map { (stage, ms) -> StageMetricUi(stage, ms, job.durationMs?.let { RtfCalculator.stage(ms, it) }) }
    }
}
