package ru.stenokot.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.app.data.AudioRecord
import ru.stenokot.app.data.CoreStages
import ru.stenokot.app.data.JobPhase
import ru.stenokot.app.data.JobRecord
import ru.stenokot.app.data.LlmRecord
import ru.stenokot.app.data.OutputRecord
import ru.stenokot.app.data.PreflightRecord
import ru.stenokot.app.domain.StageStatus
import ru.stenokot.app.domain.UserStage
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.Stage

/** Экраны получают только честные значения: доля и счётчик есть там, где они реально известны. */
class JobUiMapperTest {
    private fun job(
        phase: JobPhase = JobPhase.RUNNING,
        stage: Stage = Stage.ASR,
        completed: Set<Stage> = emptySet(),
    ) = JobRecord(
        id = "a",
        seq = 1,
        title = "x.mp3",
        stem = "x",
        sourceUri = "content://x",
        createdAtMs = 1,
        phase = phase,
        stage = stage,
        completedStages = completed,
        audio = AudioRecord(600_000, 44_100, 2, "audio/mpeg", "h"),
        preflight = PreflightRecord("x.mp3", 2_048_000, 600_000, "audio/mpeg", 10, 5),
    )

    private fun live(stage: Stage, fraction: Float?, done: Int? = null, total: Int? = null, id: String = "a") = LiveProgress(
        jobId = id,
        stage = stage,
        fraction = fraction,
        counterDone = done,
        counterTotal = total,
        stageStartedAtMs = 0,
        nowMs = 5_000,
        finishedStagesMs = 1_000,
        lowMemory = false,
        cancelling = false,
        paused = false,
    )

    private val beforeAsr = setOf(Stage.IMPORT, Stage.DECODE, Stage.VAD, Stage.LANG_ID)

    private fun row(job: JobRecord, live: LiveProgress?, stage: UserStage) =
        JobUiMapper.processing(job, live, null).stages.first { it.stage == stage }

    @Test
    fun activeStageTimeKeepsPaceWithTheRunningStage() {
        val job = job(stage = Stage.DECODE, completed = setOf(Stage.IMPORT)).copy(stageMs = mapOf(Stage.IMPORT to 2_000L))
        assertEquals(7_000L, row(job, live(Stage.DECODE, 0.4f), UserStage.PREPARE_AUDIO).tookMs)
        assertEquals(2_000L, row(job, null, UserStage.PREPARE_AUDIO).tookMs)
        assertNull(row(job, live(Stage.DECODE, 0.4f), UserStage.RECOGNIZE).tookMs)
    }

    @Test
    fun finishedStageTimeIgnoresLiveStage() {
        val job = job(completed = beforeAsr).copy(stageMs = mapOf(Stage.VAD to 1_000L))
        assertEquals(1_000L, row(job, live(Stage.ASR, 0.4f), UserStage.PREPARE_AUDIO).tookMs)
    }

    @Test
    fun finishedProtocolKeepsItsRealWindowCount() {
        val job = job(phase = JobPhase.DONE, stage = Stage.LLM)
            .copy(llm = LlmRecord(status = LlmStatus.PARTIAL, windowsDone = 1, windowsTotal = 3))
        val protocol = JobUiMapper.protocol(job, null, null, null)
        assertEquals(1, protocol.windowsDone)
        assertEquals(3, protocol.windowsTotal)
        val unknown = JobUiMapper.protocol(job.copy(llm = LlmRecord(status = LlmStatus.PARTIAL)), null, null, null)
        assertNull(unknown.windowsDone)
        assertNull(unknown.windowsTotal)
    }

    @Test
    fun verificationGetsLlmTimeFromItsOwnRecord() {
        val job = job().copy(stageMs = mapOf(Stage.ASR to 3_000L), llm = LlmRecord(status = LlmStatus.FAILED, tookMs = 78_000))
        val metrics = JobUiMapper.stageMetrics(job).associateBy { it.stage }
        assertEquals(78_000L, metrics.getValue(Stage.LLM).tookMs)
        assertEquals(3_000L, metrics.getValue(Stage.ASR).tookMs)
        assertTrue(JobUiMapper.stageMetrics(job.copy(llm = LlmRecord())).none { it.stage == Stage.LLM })
    }

    @Test
    fun activeStageShowsRealCounterAndFinishedOnesDone() {
        val job = job(completed = beforeAsr)
        val ui = JobUiMapper.processing(job, live(Stage.ASR, 0.42f, 19, 45), null)
        val stages = ui.stages.associateBy { it.stage }
        assertEquals(StageStatus.DONE, stages.getValue(UserStage.PREPARE_AUDIO).status)
        val asr = stages.getValue(UserStage.RECOGNIZE)
        assertEquals(StageStatus.ACTIVE, asr.status)
        assertEquals(19, asr.counterDone)
        assertEquals(45, asr.counterTotal)
        assertEquals(StageStatus.PENDING, stages.getValue(UserStage.DIARIZE).status)
        assertEquals(6_000L, ui.elapsedMs)
    }

    @Test
    fun savingStageNeverShowsInventedPercent() {
        val job = job(stage = Stage.MERGE, completed = CoreStages.toSet() - Stage.MERGE - Stage.VALIDATE)
        val row = row(job, live(Stage.MERGE, null), UserStage.SAVE_TRANSCRIPT)
        assertEquals(StageStatus.ACTIVE, row.status)
        assertNull(row.fraction)
        assertNull(row.counterDone)
    }

    @Test
    fun preparationFractionAccumulatesAcrossItsStages() {
        val job = job(stage = Stage.VAD, completed = setOf(Stage.IMPORT, Stage.DECODE))
        assertEquals(0.625f, checkNotNull(row(job, live(Stage.VAD, 0.5f), UserStage.PREPARE_AUDIO).fraction), 0.001f)
    }

    @Test
    fun countersUseUnitsThatScreenExpects() {
        val copying = row(job(stage = Stage.IMPORT), live(Stage.IMPORT, 0.5f), UserStage.PREPARE_AUDIO)
        assertEquals(2_000, copying.counterTotal)
        assertEquals(1_000, copying.counterDone)
        val decoding = row(
            job(stage = Stage.DECODE, completed = setOf(Stage.IMPORT)),
            live(Stage.DECODE, 0.25f),
            UserStage.PREPARE_AUDIO,
        )
        assertEquals(600, decoding.counterTotal)
        assertEquals(150, decoding.counterDone)
    }

    @Test
    fun failedStageIsMarkedAndConfirmedStagesRemain() {
        val failed = job(phase = JobPhase.FAILED, stage = Stage.ASR, completed = beforeAsr)
        val stages = JobUiMapper.processing(failed, null, null).stages.associateBy { it.stage }
        assertEquals(StageStatus.FAILED, stages.getValue(UserStage.RECOGNIZE).status)
        assertEquals(StageStatus.DONE, stages.getValue(UserStage.PREPARE_AUDIO).status)
    }

    @Test
    fun interruptedShowsLastConfirmedStageAndNothingFakeActive() {
        val interrupted = job(phase = JobPhase.INTERRUPTED, stage = Stage.ASR, completed = beforeAsr)
        val stages = JobUiMapper.processing(interrupted, null, null).stages.associateBy { it.stage }
        assertEquals(StageStatus.DONE, stages.getValue(UserStage.PREPARE_AUDIO).status)
        assertEquals(StageStatus.PENDING, stages.getValue(UserStage.RECOGNIZE).status)
        assertTrue(JobUiMapper.state(interrupted, null) is JobState.Interrupted)
    }

    @Test
    fun noSpeechSkipsRecognitionAndProtocol() {
        val silent = job(phase = JobPhase.DONE, stage = Stage.LLM, completed = CoreStages.toSet()).copy(noSpeech = true)
        val stages = JobUiMapper.processing(silent, null, null).stages.associateBy { it.stage }
        assertEquals(StageStatus.SKIPPED, stages.getValue(UserStage.RECOGNIZE).status)
        assertEquals(StageStatus.SKIPPED, stages.getValue(UserStage.BUILD_PROTOCOL).status)
    }

    @Test
    fun protocolStageFollowsItsOwnStatus() {
        val done = job(phase = JobPhase.DONE, stage = Stage.LLM, completed = CoreStages.toSet())
        fun status(llm: LlmStatus) =
            row(done.copy(llm = LlmRecord(llm)), null, UserStage.BUILD_PROTOCOL).status
        assertEquals(StageStatus.DONE, status(LlmStatus.APPLIED))
        assertEquals(StageStatus.DONE, status(LlmStatus.PARTIAL))
        assertEquals(StageStatus.FAILED, status(LlmStatus.FAILED))
        assertEquals(StageStatus.SKIPPED, status(LlmStatus.SKIPPED_LOW_MEMORY))
    }

    @Test
    fun liveOfAnotherJobDoesNotLeakIntoState() {
        val running = job(stage = Stage.ASR)
        val state = JobUiMapper.state(running, live(Stage.ASR, 0.9f, id = "other"))
        assertEquals(0f, (state as JobState.Running).fraction, 0f)
    }

    @Test
    fun remainingStaysHiddenUntilThereIsSomethingToBaseItOn() {
        val job = job(stage = Stage.DECODE, completed = setOf(Stage.IMPORT))
        val ui = JobUiMapper.processing(job, live(Stage.DECODE, null), null)
        assertNull(ui.remainingMinMs)
    }

    @Test
    fun remainingAppearsOnceAsrSpeedIsObserved() {
        val job = job(completed = beforeAsr)
        val ui = JobUiMapper.processing(job, live(Stage.ASR, 0.5f, 10, 20), null)
        assertNotNull(ui.remainingMinMs)
        assertTrue(checkNotNull(ui.remainingMaxMs) >= checkNotNull(ui.remainingMinMs))
    }

    @Test
    fun homeShowsActiveJobThenSessionResultThenIdle() {
        val running = job()
        assertEquals("RUNNING:ASR", JobUiMapper.homeStatus(listOf(running), null, emptySet()))
        val old = job(phase = JobPhase.DONE, stage = Stage.LLM).copy(llm = LlmRecord(LlmStatus.APPLIED))
        assertEquals("IDLE", JobUiMapper.homeStatus(listOf(old), null, emptySet()))
        assertEquals("DONE", JobUiMapper.homeStatus(listOf(old), null, setOf("a")))
    }

    @Test
    fun homeAlwaysSurfacesInterruptedJob() {
        val cut = job(phase = JobPhase.INTERRUPTED, stage = Stage.ASR)
        assertEquals("FAILED:INTERRUPTED", JobUiMapper.homeStatus(listOf(cut), null, emptySet()))
        val code = cut.copy(errorCode = ErrorCode.LOW_MEMORY)
        assertEquals("FAILED:LOW_MEMORY", JobUiMapper.homeStatus(listOf(code), null, emptySet()))
    }

    @Test
    fun resultExistsOnlyWithSavedFile() {
        assertNull(JobUiMapper.result(job(), null, emptyList(), null, null, null))
        val saved = job(phase = JobPhase.TRANSCRIPT_READY, stage = Stage.LLM)
            .copy(output = OutputRecord("/p/x.json", null, 10, 5, true, "sha"))
        val result = checkNotNull(JobUiMapper.result(saved, null, emptyList(), null, null, "/a.wav"))
        assertEquals("/p/x.json", result.output.path)
        assertFalse(result.protocol.running)
        assertNull(result.protocol.status)
    }

    @Test
    fun modelBytesAreShownOnlyWhileWaitingForModel() {
        val models = ru.stenokot.app.domain.ModelsUi(ru.stenokot.app.domain.ModelReadiness.COPYING, 214, 378, 0, emptyList())
        val waiting = job(phase = JobPhase.WAITING_MODEL, stage = Stage.LLM)
        assertEquals(214L, JobUiMapper.protocol(waiting, null, null, models).modelCopiedBytes)
        val running = job(stage = Stage.LLM)
        assertNull(JobUiMapper.protocol(running, null, null, models).modelCopiedBytes)
    }

    @Test
    fun diagnosticsContainNoFileNameOrTranscriptText() {
        val failed = job(phase = JobPhase.FAILED).copy(
            title = "Совещание с директором.mp3",
            stem = "Совещание с директором",
            errorCode = ErrorCode.DECODE_CORRUPT,
            errorDetail = "Не прочитан Совещание с директором.mp3",
        )
        val text = Diagnostics.encode(
            DiagnosticPackage("1", "c", "emu", 36, 1, true, true, emptyList(), listOf(Diagnostics.job(failed))),
        )
        assertFalse(text, "директор" in text)
        assertTrue("DECODE_CORRUPT" in text)
    }

    @Test
    fun rtfFullAppearsOnlyAfterLanguageModelFinished() {
        val saved = job(phase = JobPhase.TRANSCRIPT_READY, stage = Stage.LLM).copy(stageMs = mapOf(Stage.ASR to 60_000L))
        assertEquals(0.1, checkNotNull(saved.rtf().core), 1e-9)
        assertNull(saved.rtf().full)
        val done = saved.copy(phase = JobPhase.DONE, llm = LlmRecord(LlmStatus.APPLIED, tookMs = 60_000))
        assertEquals(0.2, checkNotNull(done.rtf().full), 1e-9)
    }
}
