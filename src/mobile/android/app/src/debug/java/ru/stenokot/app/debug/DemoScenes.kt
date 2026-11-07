package ru.stenokot.app.debug

import ru.stenokot.app.domain.AudioFileUi
import ru.stenokot.app.domain.HomeUi
import ru.stenokot.app.domain.JobDetailUi
import ru.stenokot.app.domain.JobSummaryUi
import ru.stenokot.app.domain.ModelsUi
import ru.stenokot.app.domain.OutputFileUi
import ru.stenokot.app.domain.ProcessingUi
import ru.stenokot.app.domain.ProtocolUi
import ru.stenokot.app.domain.ResultUi
import ru.stenokot.app.domain.StageStatus
import ru.stenokot.app.domain.StageUi
import ru.stenokot.app.domain.UserStage
import ru.stenokot.app.domain.VerificationUi
import ru.stenokot.app.ui.format.STATUS_IDLE
import ru.stenokot.app.ui.format.statusCode
import ru.stenokot.app.ui.nav.ResultTab
import ru.stenokot.app.ui.nav.Route
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.PauseReason
import ru.stenokot.core.model.PreflightInfo
import ru.stenokot.core.model.Stage
import ru.stenokot.core.model.Utterance
import ru.stenokot.core.protocol.ProtocolDocument

/** Настоящий файл для плеера. Кладётся на устройство вручную, в репозиторий аудио не попадает. */
internal object DemoAudio {
    /** Путь к файлу или null: тогда экран показывает, что аудио недоступно. */
    @Volatile
    var path: String? = null
}

/** Срез состояния приложения для одного состояния из дизайн-брифа. */
internal data class DemoScene(
    val home: HomeUi,
    val jobs: List<JobDetailUi>,
    val route: Route,
    val verification: VerificationUi = DemoData.verificationEmpty,
)

/** Обработка DS-1 с параметрами конкретного состояния. */
internal data class StageSnapshot(
    val active: UserStage?,
    val fraction: Float?,
    val done: Int?,
    val total: Int?,
    val tookMs: Map<UserStage, Long>,
    val activeMs: Long?,
)

private const val MIN = 60_000L

private val TookPrepare = UserStage.PREPARE_AUDIO to 20_000L
private val TookAsr = UserStage.RECOGNIZE to 232_000L
private val TookDiar = UserStage.DIARIZE to 68_000L

/** Строит пять строк рельса: этапы до активного готовы, после него ждут. */
internal fun railStages(snapshot: StageSnapshot): List<StageUi> {
    val activeIndex = snapshot.active?.ordinal ?: -1
    return UserStage.entries.map { stage ->
        when {
            stage == snapshot.active -> StageUi(
                stage = stage,
                status = StageStatus.ACTIVE,
                fraction = snapshot.fraction,
                counterDone = snapshot.done,
                counterTotal = snapshot.total,
                tookMs = snapshot.activeMs,
            )
            snapshot.tookMs.containsKey(stage) || stage.ordinal < activeIndex -> StageUi(
                stage = stage,
                status = StageStatus.DONE,
                fraction = null,
                counterDone = null,
                counterTotal = null,
                tookMs = snapshot.tookMs[stage],
            )
            else -> StageUi(stage, StageStatus.PENDING, null, null, null, null)
        }
    }
}

private fun processing(
    snapshot: StageSnapshot,
    elapsedMs: Long,
    remainingMin: Long? = null,
    remainingMax: Long? = null,
    lowMemory: Boolean = false,
    cancelling: Boolean = false,
    powerSave: Boolean = false,
    resumed: Boolean = false,
    preflight: PreflightInfo = DemoData.preflightDs1,
) = ProcessingUi(
    preflight = preflight,
    stages = railStages(snapshot),
    elapsedMs = elapsedMs,
    remainingMinMs = remainingMin,
    remainingMaxMs = remainingMax,
    lowMemory = lowMemory,
    cancelling = cancelling,
    powerSave = powerSave,
    resumed = resumed,
)

private val AsrSnapshot = StageSnapshot(
    active = UserStage.RECOGNIZE,
    fraction = 19f / 45f,
    done = 19,
    total = 45,
    tookMs = mapOf(TookPrepare),
    activeMs = 97_000,
)

private val Ds1Summary = DemoData.summary(
    id = "ds1",
    title = DemoData.FILE_DS1,
    createdAtMs = DemoData.at(11, 6, 14, 20),
    durationMs = DemoData.DURATION_DS1,
    speakers = 3,
    state = JobState.Queued,
)

private fun ds1(state: JobState, work: ProcessingUi? = null, result: ResultUi? = null) =
    JobDetailUi(Ds1Summary.copy(state = state), work, result, diagnostics = null)

private fun output(stem: String, exportFailed: Boolean = false) = OutputFileUi(
    path = "Download/Stenokot/$stem.json",
    sizeBytes = DemoData.mib(0.05),
    createdAtMs = DemoData.at(11, 6, 14, 26),
    validated = true,
    sha256 = "demo-not-a-real-hash",
    exportFailed = exportFailed,
)

private fun protocol(
    status: LlmStatus?,
    running: Boolean = false,
    windowsDone: Int? = null,
    windowsTotal: Int? = 8,
    copied: Long? = null,
    total: Long? = null,
    document: ProtocolDocument? = null,
    error: ErrorCode? = null,
    retryUsed: Boolean = false,
) = ProtocolUi(status, running, windowsDone, windowsTotal, copied, total, retryUsed, document, error)

private fun result(
    title: String,
    durationMs: Long,
    speakers: Int,
    utterances: List<Utterance>,
    proto: ProtocolUi,
    language: Language = Language.RU,
    exportFailed: Boolean = false,
) = ResultUi(
    title = title,
    durationMs = durationMs,
    language = language,
    speakers = speakers,
    utterances = utterances,
    audioPath = DemoAudio.path,
    output = output(title.substringBeforeLast('.'), exportFailed),
    protocol = proto,
)

private fun ds1Result(proto: ProtocolUi, exportFailed: Boolean = false) =
    result(DemoData.FILE_DS1, DemoData.DURATION_DS1, 3, DemoData.utterancesDs1, proto, exportFailed = exportFailed)

private fun home(
    models: ModelsUi = DemoData.modelsReady,
    audio: List<AudioFileUi> = listOf(DemoData.audioDs1, DemoData.audioDs2, DemoData.audioDs5),
    jobs: List<JobSummaryUi> = listOf(DemoData.historyDs8, DemoData.historyDs3),
    permission: Boolean = true,
    status: String = STATUS_IDLE,
) = HomeUi(models, permission, audio, jobs, status)

private fun processingScene(state: JobState, work: ProcessingUi): DemoScene {
    val detail = ds1(state, work)
    return DemoScene(
        home = home(jobs = listOf(detail.summary, DemoData.historyDs8), status = statusCode(state)),
        jobs = listOf(detail),
        route = Route.Processing("ds1"),
    )
}

private fun running(stage: Stage, fraction: Float) = JobState.Running(stage, fraction)

private fun done(llm: LlmStatus) = JobState.Done(llm, emptyList())

private fun resultScene(
    state: JobState,
    res: ResultUi,
    tab: ResultTab = ResultTab.TRANSCRIPT,
    focus: Int? = null,
    id: String = "ds1",
): DemoScene {
    val summary = DemoData.summary(
        id = id,
        title = res.title,
        createdAtMs = DemoData.at(11, 6, 14, 26),
        durationMs = res.durationMs,
        speakers = res.speakers,
        state = state,
    )
    return DemoScene(
        home = home(jobs = listOf(summary, DemoData.historyDs8), status = statusCode(state)),
        jobs = listOf(JobDetailUi(summary, null, res, diagnostics = null)),
        route = Route.Result(id, tab, focus),
    )
}

private fun protocolStateScene(transcriptState: DemoScene): DemoScene {
    val route = transcriptState.route as Route.Result
    return transcriptState.copy(route = route.copy(tab = ResultTab.PROTOCOL))
}

private fun failureScene(
    state: JobState,
    title: String = DemoData.FILE_DS1,
    preflight: PreflightInfo? = null,
): DemoScene {
    val summary = DemoData.summary(
        id = "ds1",
        title = title,
        createdAtMs = DemoData.at(11, 6, 14, 20),
        durationMs = null,
        speakers = null,
        state = state,
    )
    val work = preflight?.let { processing(StageSnapshot(null, null, null, null, emptyMap(), null), 0, preflight = it) }
    return DemoScene(
        home = home(jobs = listOf(summary, DemoData.historyDs8), status = statusCode(state)),
        jobs = listOf(JobDetailUi(summary, work, null, diagnostics = "код: ${statusCode(state)}")),
        route = Route.Failure("ds1"),
    )
}

private fun verificationScene(models: ModelsUi, verification: VerificationUi, status: String = STATUS_IDLE) = DemoScene(
    home = home(models = models, status = status),
    jobs = emptyList(),
    route = Route.Verification,
    verification = verification,
)

/** Состояния главного экрана, включая статусы очереди. */
private fun homeScene(id: String): DemoScene? {
    val empty = home(audio = emptyList(), jobs = emptyList(), permission = false)
    return when (id) {
        "S01-A" -> DemoScene(empty, emptyList(), Route.Home)
        "S01-B" -> DemoScene(empty.copy(models = DemoData.modelsCopying), emptyList(), Route.Home)
        "S01-C" -> {
            val detail = ds1(
                JobState.Queued,
                processing(StageSnapshot(null, null, null, null, emptyMap(), null), 0),
            )
            DemoScene(
                home(
                    audio = emptyList(),
                    jobs = listOf(detail.summary.copy(queuePosition = 1)),
                    status = "QUEUED",
                ),
                listOf(detail),
                Route.Home,
            )
        }
        "S01-D" -> DemoScene(home(), emptyList(), Route.Home)
        "S01-E" -> {
            val history = DemoData.historyDs8.copy(
                id = "ds4",
                title = DemoData.FILE_DS4,
                durationMs = DemoData.DURATION_DS4,
                speakers = 5,
            )
            DemoScene(
                home(audio = listOf(DemoData.audioDs4, DemoData.audioDs5), jobs = listOf(history)),
                emptyList(),
                Route.Home,
            )
        }
        "S01-F" -> {
            val state = running(Stage.ASR, 19f / 45f)
            val detail = ds1(state, processing(AsrSnapshot, 117_000, 3 * MIN, 5 * MIN))
            val queued = DemoData.summary(
                "ds2",
                DemoData.FILE_DS2,
                DemoData.at(11, 6, 14, 22),
                DemoData.DURATION_DS2,
                null,
                JobState.Queued,
                1,
            )
            DemoScene(
                home(
                    jobs = listOf(detail.summary, queued, DemoData.historyDs8, DemoData.historyDs3),
                    status = statusCode(state),
                ),
                listOf(detail),
                Route.Home,
            )
        }
        "S01-G" -> DemoScene(home(models = DemoData.modelsNoLlm), emptyList(), Route.Home)
        "S01-G2" -> DemoScene(home(models = DemoData.modelsNoAsr), emptyList(), Route.Home)
        "S01-G3" -> DemoScene(home(models = DemoData.modelsNoSpace), emptyList(), Route.Home)
        "S01-Q" -> {
            val state = running(Stage.ASR, 19f / 45f)
            val detail = ds1(state, processing(AsrSnapshot, 117_000, 3 * MIN, 5 * MIN))
            fun job(n: Int, s: JobState, position: Int? = null) = DemoData.summary(
                "q$n",
                "zapis_soveshchaniya_$n.mp3",
                DemoData.at(11, 6, 9, n),
                1_500_000L + n * 60_000,
                if (position == null) 2 else null,
                s,
                position,
            )
            val jobs = listOf(
                detail.summary,
                job(1, JobState.Queued, 1),
                job(2, JobState.Queued, 2),
                job(3, JobState.Paused(Stage.ASR, PauseReason.QUEUED_BEHIND_OTHER_JOB), 3),
                job(4, done(LlmStatus.APPLIED)),
                job(5, JobState.TranscriptReady("Download/Stenokot/zapis_soveshchaniya_5.json")),
                job(6, JobState.Failed(Stage.DECODE, ErrorCode.DECODE_CORRUPT, null)),
                job(7, JobState.Cancelled),
                job(8, done(LlmStatus.SKIPPED_LOW_MEMORY)),
            )
            DemoScene(home(audio = emptyList(), jobs = jobs, status = statusCode(state)), listOf(detail), Route.Home)
        }
        "S01-R" -> {
            val state = JobState.Interrupted(Stage.DIARIZE, ErrorCode.FGS_TIMEOUT)
            val detail = ds1(state)
            DemoScene(
                home(jobs = listOf(detail.summary, DemoData.historyDs8), status = STATUS_IDLE),
                listOf(detail),
                Route.Home,
            )
        }
        else -> null
    }
}

/** Состояния экрана обработки. */
private fun processingScene(id: String): DemoScene? = when (id) {
    "S03-A" -> processingScene(
        running(Stage.DECODE, 515f / 768f),
        processing(
            StageSnapshot(UserStage.PREPARE_AUDIO, 515f / 768f, 515, 768, emptyMap(), 5_000),
            elapsedMs = 5_000,
        ),
    )
    "S03-B" -> processingScene(
        running(Stage.ASR, 19f / 45f),
        processing(AsrSnapshot, 117_000, 3 * MIN, 5 * MIN),
    )
    "S03-C" -> processingScene(
        running(Stage.DIARIZE, 1f / 3f),
        processing(
            StageSnapshot(UserStage.DIARIZE, 1f / 3f, 1, 3, mapOf(TookPrepare, TookAsr), 23_000),
            275_000,
            1 * MIN,
            2 * MIN,
        ),
    )
    "S03-D" -> processingScene(
        running(Stage.VALIDATE, 0f),
        processing(
            StageSnapshot(UserStage.SAVE_TRANSCRIPT, null, null, null, mapOf(TookPrepare, TookAsr, TookDiar), 2_000),
            322_000,
        ),
    )
    "S03-E" -> processingScene(
        JobState.Paused(Stage.ASR, PauseReason.THERMAL),
        processing(AsrSnapshot, 117_000),
    )
    "S03-F" -> processingScene(
        running(Stage.ASR, 19f / 45f),
        processing(AsrSnapshot, 117_000, cancelling = true),
    )
    "S03-G" -> processingScene(
        JobState.Cancelled,
        processing(StageSnapshot(null, null, null, null, mapOf(TookPrepare), null), 117_000),
    )
    "S03-H" -> processingScene(
        running(Stage.ASR, 19f / 45f),
        processing(AsrSnapshot, 117_000, 3 * MIN, 5 * MIN, lowMemory = true),
    )
    "S03-H2" -> processingScene(
        running(Stage.ASR, 19f / 45f),
        processing(AsrSnapshot, 117_000, 3 * MIN, 5 * MIN, powerSave = true),
    )
    "S03-R" -> processingScene(
        running(Stage.DIARIZE, 1f / 3f),
        processing(
            StageSnapshot(UserStage.DIARIZE, 1f / 3f, 1, 3, mapOf(TookPrepare, TookAsr), 23_000),
            275_000,
            resumed = true,
        ),
    )
    "S03-I" -> processingScene(
        running(Stage.IMPORT, 7.9f / 11.7f),
        processing(
            StageSnapshot(UserStage.PREPARE_AUDIO, 7.9f / 11.7f, 8_090, 11_981, emptyMap(), 1_000),
            elapsedMs = 1_000,
        ),
    )
    "S03-L" -> processingScene(
        running(Stage.LANG_ID, 1f / 3f),
        processing(
            StageSnapshot(UserStage.PREPARE_AUDIO, 1f / 3f, 1, 3, emptyMap(), 15_000),
            elapsedMs = 15_000,
            preflight = DemoData.preflightDs4Rough,
        ),
    )
    else -> null
}

/** Состояния результата и протокола. */
private fun resultScene(id: String): DemoScene? {
    val applied = protocol(LlmStatus.APPLIED, windowsDone = 8, document = DemoData.protocolDs1)
    return when (id) {
        "S04-A" -> resultScene(running(Stage.LLM, 3f / 8f), ds1Result(protocol(null, running = true, windowsDone = 3)))
        "S04-B" -> resultScene(
            JobState.WaitingModel,
            ds1Result(protocol(null, copied = DemoData.mib(214.0), total = DemoData.mib(378.0))),
        )
        "S04-C" -> resultScene(done(LlmStatus.APPLIED), ds1Result(applied))
        "S04-D" -> resultScene(
            done(LlmStatus.PARTIAL),
            ds1Result(protocol(LlmStatus.PARTIAL, windowsDone = 4, document = DemoData.protocolDs1Partial)),
        )
        "S04-E" -> resultScene(done(LlmStatus.DEFERRED), ds1Result(protocol(LlmStatus.DEFERRED, windowsDone = 2)))
        "S04-F" -> resultScene(
            done(LlmStatus.SKIPPED_LOW_MEMORY),
            ds1Result(protocol(LlmStatus.SKIPPED_LOW_MEMORY, error = ErrorCode.LLM_LOW_MEMORY)),
        )
        "S04-G" -> resultScene(
            done(LlmStatus.SKIPPED_BY_BUDGET),
            ds1Result(protocol(LlmStatus.SKIPPED_BY_BUDGET, windowsDone = 0, error = ErrorCode.LLM_TIMEOUT)),
        )
        "S04-H" -> resultScene(
            done(LlmStatus.FAILED),
            ds1Result(protocol(LlmStatus.FAILED, error = ErrorCode.LLM_DIED_OOM)),
        )
        "S04-I" -> resultScene(
            JobState.Done(LlmStatus.SKIPPED_BY_BUDGET, listOf("AUDIO_EMPTY")),
            result(DemoData.FILE_DS6, DemoData.DURATION_DS6, 0, emptyList(), protocol(null, windowsTotal = 0)),
            id = "ds6",
        )
        "S04-J" -> resultScene(
            done(LlmStatus.APPLIED),
            result(
                DemoData.FILE_DS5,
                DemoData.DURATION_DS5,
                2,
                DemoData.utterancesDs5,
                protocol(LlmStatus.APPLIED, windowsDone = 3, windowsTotal = 3),
                language = Language.EN,
            ),
            id = "ds5",
        )
        "S04-K" -> resultScene(
            done(LlmStatus.APPLIED),
            ds1Result(applied),
            focus = DemoData.utterancesDs1.first { it.startMs == 192_000L }.id,
        )
        "S04-L" -> resultScene(
            done(LlmStatus.APPLIED),
            result(DemoData.FILE_DS2, DemoData.DURATION_DS2, 8, DemoData.utterancesDs2, applied),
            id = "ds2",
        )
        "S04-M" -> resultScene(done(LlmStatus.APPLIED), ds1Result(applied), focus = 6)
        "S04-N" -> resultScene(
            done(LlmStatus.PARTIAL),
            result(
                DemoData.FILE_DS3,
                DemoData.DURATION_DS3,
                3,
                DemoData.utterancesDs3,
                protocol(LlmStatus.PARTIAL, windowsDone = 6, windowsTotal = 40),
            ),
            focus = DemoData.utterancesDs3.first { it.startMs == 5_047_000L }.id,
            id = "ds3",
        )
        "S04-O" -> resultScene(
            done(LlmStatus.APPLIED),
            result(DemoData.FILE_DS4, DemoData.DURATION_DS4, 5, DemoData.utterancesDs2.take(742), applied),
            id = "ds4",
        )
        "S04-P" -> resultScene(done(LlmStatus.APPLIED), ds1Result(applied))
        "S04-Q" -> resultScene(done(LlmStatus.APPLIED), ds1Result(applied, exportFailed = true))
        "S04-X" -> resultScene(
            done(LlmStatus.APPLIED),
            result(
                DemoData.FILE_PERF,
                DemoData.DURATION_PERF,
                6,
                DemoData.utterancesPerf,
                protocol(LlmStatus.APPLIED, windowsDone = 8, document = DemoData.protocolDs1),
            ),
            id = "perf",
        )
        "S04-E2" -> resultScene(
            done(LlmStatus.CANCELLED_BY_NEW_JOB),
            ds1Result(protocol(LlmStatus.CANCELLED_BY_NEW_JOB, windowsDone = 2, retryUsed = true)),
        )
        "S04-H2" -> resultScene(
            done(LlmStatus.FAILED),
            ds1Result(protocol(LlmStatus.FAILED, error = ErrorCode.LLM_MODEL_UNAVAILABLE)),
        )
        "S04-R" -> resultScene(JobState.TranscriptReady("Download/Stenokot/x.json"), ds1Result(protocol(null)))
        "S04-A2" -> resultScene(
            running(Stage.LLM, 0f),
            ds1Result(protocol(null, running = true, windowsDone = 0)),
        )
        "S05-A" -> protocolStateScene(resultScene("S04-C")!!)
        "S05-B" -> protocolStateScene(resultScene("S04-A")!!)
        "S05-C" -> protocolStateScene(resultScene("S04-D")!!)
        "S05-D" -> protocolStateScene(resultScene("S04-E")!!)
        "S05-E" -> protocolStateScene(resultScene("S04-F")!!)
        "S05-F" -> protocolStateScene(resultScene("S04-G")!!)
        "S05-G" -> protocolStateScene(resultScene("S04-H")!!)
        "S05-H" -> resultScene(
            done(LlmStatus.APPLIED),
            result(
                DemoData.FILE_DS8,
                DemoData.DURATION_DS8,
                2,
                DemoData.utterancesDs1.take(214),
                protocol(LlmStatus.APPLIED, windowsDone = 6, windowsTotal = 6, document = DemoData.protocolDs8),
            ),
            tab = ResultTab.PROTOCOL,
            id = "ds8",
        )
        "S05-I" -> protocolStateScene(resultScene("S04-I")!!)
        "S05-G2" -> protocolStateScene(resultScene("S04-H2")!!)
        "S05-D2" -> protocolStateScene(resultScene("S04-E2")!!)
        else -> null
    }
}

/** Состояния ошибок и экрана проверки. */
private fun otherScene(id: String): DemoScene? = when (id) {
    "S07-A" -> failureScene(JobState.Failed(Stage.ASR, ErrorCode.MODEL_LOAD_FAILED, null))
    "S07-B" -> failureScene(
        JobState.Failed(Stage.DECODE, ErrorCode.DECODE_UNSUPPORTED_FORMAT, null),
        "prezentaciya_kvartal.pdf",
    )
    "S07-C" -> failureScene(JobState.Failed(Stage.DECODE, ErrorCode.DECODE_CORRUPT, null), "obryv_zapisi.mp3")
    "S07-D" -> failureScene(JobState.Failed(Stage.IMPORT, ErrorCode.IMPORT_READ_FAILED, null), "zapis_s_fleshki.m4a")
    "S07-E" -> failureScene(JobState.Failed(Stage.IMPORT, ErrorCode.STORAGE_LOW, null))
    "S07-F" -> failureScene(JobState.Interrupted(Stage.ASR, ErrorCode.LOW_MEMORY))
    "S07-G" -> failureScene(JobState.Failed(Stage.VALIDATE, ErrorCode.CONTRACT_INVALID, null))
    "S07-H" -> failureScene(JobState.Interrupted(Stage.DIARIZE, ErrorCode.FGS_TIMEOUT))
    "S08-A" -> verificationScene(DemoData.modelsReady, DemoData.verificationEmpty)
    "S08-B" -> verificationScene(DemoData.modelsCopying, DemoData.verificationCopying)
    "S08-C" -> verificationScene(DemoData.modelsReady, DemoData.verificationMetrics, "DONE")
    "S08-D" -> verificationScene(DemoData.modelsNoLlm, DemoData.verificationCopying)
    else -> null
}

/** Сцена по идентификатору состояния из брифа или null, если такого состояния нет. */
internal fun demoScene(id: String): DemoScene? =
    homeScene(id) ?: processingScene(id) ?: resultScene(id) ?: otherScene(id)
