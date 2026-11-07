package ru.stenokot.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.Stage
import ru.stenokot.core.storage.AsrJournalLine
import java.io.File

/** Файлы задачи: атомарность, журналы и сверка после смерти процесса. */
class StorageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun record(
        id: String = "a",
        phase: JobPhase = JobPhase.RUNNING,
        stage: Stage = Stage.ASR,
        completed: Set<Stage> = emptySet(),
        seq: Long = 1,
    ) = JobRecord(
        id = id,
        seq = seq,
        title = "x.mp3",
        stem = "x",
        sourceUri = "content://x",
        createdAtMs = 1,
        phase = phase,
        stage = stage,
        completedStages = completed,
        sourceFile = "source.mp3",
    )

    private fun files(vararg names: String): JobFiles {
        val dir = folder.newFolder()
        names.forEach { File(dir, it).writeText("x") }
        return JobFiles(dir)
    }

    @Test
    fun fileReadInPlaceNeedsNoCopyInTheJobFolder() {
        val running = record(completed = setOf(Stage.IMPORT), phase = JobPhase.RUNNING, stage = Stage.DECODE)
            .copy(sourceFile = null, sourceInPlace = true)
        val result = JobReconciler.reconcile(running, files("job.json"))
        assertEquals(JobPhase.INTERRUPTED, result.phase)
        assertEquals(Stage.DECODE, result.stage)
        assertTrue(Stage.IMPORT in result.completedStages)
    }

    @Test
    fun atomicReplaceLeavesNoTemporaryFile() {
        val target = File(folder.root, "job.json")
        AtomicFiles.replaceText(target, "старое")
        AtomicFiles.replaceText(target, "новое")
        assertEquals("новое", target.readText())
        assertFalse(File(folder.root, "job.json.tmp").exists())
    }

    @Test
    fun storeRoundTripKeepsEveryField() {
        val store = JobStore { folder.root }
        val saved = record(completed = setOf(Stage.IMPORT, Stage.DECODE)).copy(
            warnings = listOf("LOW_MEMORY"),
            llm = LlmRecord(status = LlmStatus.DEFERRED, retryUsed = true),
            stageMs = mapOf(Stage.DECODE to 12L),
        )
        store.save(saved)
        assertEquals(listOf(saved), store.loadAll())
    }

    @Test
    fun storeSkipsBrokenJobAndKeepsQueueOrder() {
        val store = JobStore { folder.root }
        store.save(record(id = "late", seq = 9))
        store.save(record(id = "early", seq = 2))
        File(folder.root, "broken").mkdirs()
        File(folder.root, "broken/job.json").writeText("{обрыв")
        assertEquals(listOf("early", "late"), store.loadAll().map { it.id })
    }

    @Test
    fun journalDropsTornTailAndAppendsCleanly() {
        val file = File(folder.root, "asr.jsonl")
        val journal = AsrJournal(file)
        journal.append(AsrJournalLine(0, 0, 10, "раз"))
        journal.append(AsrJournalLine(1, 10, 20, "два"))
        file.appendText("{\"index\":2,\"start_sa")

        assertEquals(listOf(0, 1), journal.restore().map { it.index })
        journal.append(AsrJournalLine(2, 20, 30, "три"))
        assertEquals(listOf("раз", "два", "три"), AsrJournal(file).restore().map { it.text })
    }

    @Test
    fun journalStopsAtCorruptLineInTheMiddle() {
        val file = File(folder.root, "asr.jsonl")
        AsrJournal(file).append(AsrJournalLine(0, 0, 10, "раз"))
        file.appendText("мусор\n")
        AsrJournal(file).append(AsrJournalLine(1, 10, 20, "два"))
        assertEquals(listOf(0), AsrJournal(file).restore().map { it.index })
    }

    @Test
    fun attemptLogAllowsTwoTriesPerIndex() {
        val log = AttemptLog(File(folder.root, "asr.attempt"))
        assertTrue(log.begin(3))
        assertTrue(log.begin(3))
        assertFalse(log.begin(3))
        assertTrue("другой срез считается заново", log.begin(4))
        assertTrue("счётчик живёт в файле", AttemptLog(File(folder.root, "asr.attempt")).begin(4))
        assertFalse(AttemptLog(File(folder.root, "asr.attempt")).begin(4))
    }

    @Test
    fun runningJobBecomesInterruptedAtFirstUnfinishedStage() {
        val files = files("source.mp3", "pcm16k.wav", "vad.json")
        val done = setOf(Stage.IMPORT, Stage.DECODE, Stage.VAD)
        val result = JobReconciler.reconcile(record(completed = done), files, ErrorCode.LOW_MEMORY)
        assertEquals(JobPhase.INTERRUPTED, result.phase)
        assertEquals(Stage.LANG_ID, result.stage)
        assertEquals(ErrorCode.LOW_MEMORY, result.errorCode)
    }

    @Test
    fun stageWithoutItsFileIsNotConfirmedAndLaterOnesFallToo() {
        val files = files("source.mp3", "vad.json")
        val done = setOf(Stage.IMPORT, Stage.DECODE, Stage.VAD)
        val result = JobReconciler.reconcile(record(completed = done), files)
        assertEquals(setOf(Stage.IMPORT), result.completedStages)
        assertEquals(Stage.DECODE, result.stage)
    }

    @Test
    fun deathDuringLanguageModelKeepsTranscriptAndDefers() {
        val files = files("source.mp3", "pcm16k.wav", "vad.json", "lang.json", "asr.jsonl", "turns.json", "merged.json", "transcript.json")
        val done = CoreStages.toSet()
        val running = record(stage = Stage.LLM, completed = done)
        val result = JobReconciler.reconcile(running, files)
        assertEquals(JobPhase.DONE, result.phase)
        assertEquals(LlmStatus.DEFERRED, result.llm.status)
    }

    @Test
    fun missingTranscriptWithSourceIsResumableNotLost() {
        val files = files("source.mp3", "pcm16k.wav", "vad.json", "lang.json", "asr.jsonl", "turns.json", "merged.json")
        val finished = record(phase = JobPhase.DONE, stage = Stage.LLM, completed = CoreStages.toSet())
        val result = JobReconciler.reconcile(finished, files)
        assertEquals(JobPhase.INTERRUPTED, result.phase)
        assertEquals(Stage.VALIDATE, result.stage)
    }

    @Test
    fun nothingLeftToRebuildFromIsReportedAsLostResult() {
        val finished = record(phase = JobPhase.DONE, stage = Stage.LLM, completed = CoreStages.toSet())
        val result = JobReconciler.reconcile(finished, files())
        assertEquals(JobPhase.FAILED, result.phase)
        assertEquals(RESULT_LOST_DETAIL, result.errorDetail)
    }

    @Test
    fun silentRecordingHasNoAsrFilesAndStillConfirms() {
        val files = files("source.mp3", "pcm16k.wav", "vad.json", "merged.json", "transcript.json")
        val silent = record(phase = JobPhase.DONE, stage = Stage.LLM, completed = CoreStages.toSet()).copy(noSpeech = true)
        val result = JobReconciler.reconcile(silent, files)
        assertEquals(JobPhase.DONE, result.phase)
    }

    @Test
    fun copyCutByDeathWaitsLikeAnyOtherInterruptedJob() {
        val copying = record(stage = Stage.IMPORT)
        val result = JobReconciler.reconcile(copying, files())
        assertEquals(JobPhase.INTERRUPTED, result.phase)
        assertEquals(1, result.deaths)
    }

    @Test
    fun queueOfPastLaunchIsInterruptedSoNewFileGoesFirst() {
        val waiting = record(phase = JobPhase.QUEUED, stage = Stage.IMPORT)
        val result = JobReconciler.reconcile(waiting, files())
        assertEquals(JobPhase.INTERRUPTED, result.phase)
        assertEquals(0, result.deaths)
    }

    @Test
    fun forceStopForbidsContinuingWithoutTap() {
        val running = record(phase = JobPhase.RUNNING, stage = Stage.IMPORT)
        assertTrue(JobReconciler.reconcile(running, files(), stoppedByUser = true).manualResume)
        assertFalse(JobReconciler.reconcile(running, files()).manualResume)
    }

    @Test
    fun partialFilesOfDeadProcessAreDropped() {
        val store = JobStore { folder.root }
        val dir = store.files("a").dir.apply { mkdirs() }
        File(dir, "pcm16k.wav.tmp").writeText("x")
        File(dir, "pcm16k.wav.tmp.part").writeText("x")
        File(dir, "job.json").writeText("x")
        store.dropPartial("a")
        assertEquals(listOf("job.json"), dir.list()?.toList())
    }

    @Test
    fun terminalStatesStayAsTheyAre() {
        val failed = record(phase = JobPhase.FAILED).copy(errorCode = ErrorCode.DECODE_CORRUPT)
        assertEquals(JobPhase.FAILED, JobReconciler.reconcile(failed, files()).phase)
        assertEquals(JobPhase.CANCELLED, JobReconciler.reconcile(record(phase = JobPhase.CANCELLED), files()).phase)
    }
}
