package ru.stenokot.app.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.stenokot.app.data.AsrJournal
import ru.stenokot.app.data.DiarJournal
import ru.stenokot.app.data.JobPhase
import ru.stenokot.core.contract.ValidationResult
import ru.stenokot.core.llm.LlmBudgetPolicy
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.LlmResult
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.META_PUBLISH_FAILED
import ru.stenokot.core.model.SpeakerTurn
import ru.stenokot.core.model.Stage

/** Поведение очереди, стадий, чекпойнтов и языковой модели на виртуальном времени. */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineRunnerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.harness(block: Harness.() -> Unit = {}) =
        Harness(this, folder.root).apply(block)

    private fun done(id: String) = { jobs: Map<String, ru.stenokot.app.data.JobRecord> ->
        jobs[id]?.phase == JobPhase.DONE
    }

    @Test
    fun editOfFinishedJobRepublishesMetadataWithNewHash() = runTest {
        val h = harness()
        val id = h.import("meeting")
        h.await(done(id))
        h.outputs.meta.clear()

        val ok = h.runner.updateEdited(id) { job -> job.copy(output = job.output?.copy(sha256 = "edited")) }

        assertTrue(ok)
        assertEquals(listOf("meeting:metadata"), h.outputs.meta)
        assertTrue(h.store.files(id).metadata.readText().contains("\"edited\""))
    }

    @Test
    fun jobPassesAllStagesAndSavesJsonBeforeLanguageModel() = runTest {
        val h = harness()
        val id = h.import("meeting")
        h.await(done(id))

        assertEquals(LlmStatus.APPLIED, h.runner.jobs.value.getValue(id).llm.status)
        assertEquals(
            listOf(
                "QUEUED",
                "RUNNING:IMPORT",
                "RUNNING:DECODE",
                "RUNNING:VAD",
                "RUNNING:LANG_ID",
                "RUNNING:ASR",
                "RUNNING:DIARIZE",
                "RUNNING:MERGE",
                "RUNNING:VALIDATE",
                "TRANSCRIPT_READY",
                "RUNNING:LLM",
                "DONE",
            ),
            h.statuses,
        )
        assertNotNull(h.outputs.transcripts["meeting"])
        assertTrue("meeting:metadata" in h.outputs.meta)
        assertEquals(0, h.engines.openHeavy)
        assertEquals(1, h.engines.peakHeavy)
    }

    @Test
    fun threeSecondPauseSeparatesJsonFromLanguageModel() = runTest {
        val h = harness()
        var llmAt = -1L
        var saved = -1L
        h.engines.llmBehavior = {
            llmAt = testScheduler.currentTime
            LlmResult(LlmStatus.APPLIED, null, null)
        }
        val id = h.import("a")
        h.await { it[id]?.phase == JobPhase.TRANSCRIPT_READY }
        saved = testScheduler.currentTime
        h.await(done(id))
        assertTrue("LLM через ${llmAt - saved} мс", llmAt - saved >= 3_000)
    }

    @Test
    fun queueIsStrictFifoAndNeverRunsTwoPipelinesAtOnce() = runTest {
        val h = harness()
        val first = h.import("first")
        val second = h.import("second")
        val third = h.import("third")
        h.await { jobs -> listOf(first, second, third).all { jobs[it]?.phase == JobPhase.DONE } }

        val order = h.engines.events.filter { it.startsWith("decode:") }
        assertEquals(listOf("decode:$first", "decode:$second", "decode:$third"), order)
        assertEquals(1, h.engines.peakHeavy)
    }

    @Test
    fun checkpointsMakeResumeContinueInsteadOfRestart() = runTest {
        val h = Harness(this, folder.root, engines = FakeEngines(segmentCount = 12))
        h.engines.onTranscribe = { index ->
            if (index == 6) {
                h.die()
                throw CancellationException("смерть процесса")
            }
        }
        val id = h.import("long")
        h.await { it[id]?.stage == Stage.ASR && it[id]?.phase == JobPhase.RUNNING }
        delay(1_000)

        val journal = AsrJournal(h.store.files(id).asr).restore().map { it.index }.toSet()
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 8), journal)

        val reopened = Harness(this, folder.root, engines = FakeEngines(segmentCount = 12))
        reopened.await { it[id]?.phase == JobPhase.INTERRUPTED }
        assertEquals(Stage.ASR, reopened.runner.jobs.value.getValue(id).stage)

        reopened.runner.resume(id)
        reopened.await(done(id))
        assertEquals(setOf(6, 7, 9, 10, 11), reopened.engines.transcribed.toSet())
        assertEquals(5, reopened.engines.transcribed.size)
        assertFalse("декодирование не повторяется", reopened.engines.events.any { it.startsWith("decode") })
        assertTrue(reopened.outputs.transcripts.containsKey("long"))
    }

    @Test
    fun deathDuringLanguageIdKeepsFinishedProbes() = runTest {
        val h = Harness(this, folder.root, engines = FakeEngines(segmentCount = 12))
        h.engines.onTranscribe = { index ->
            if (index == 5) {
                h.die()
                throw CancellationException("смерть процесса")
            }
        }
        val id = h.import("probe")
        h.await { it[id]?.stage == Stage.LANG_ID }
        delay(1_000)
        assertEquals(listOf(2), AsrJournal(h.store.files(id).asr).restore().map { it.index })

        val reopened = Harness(this, folder.root, engines = FakeEngines(segmentCount = 12))
        reopened.await { it[id]?.phase == JobPhase.INTERRUPTED }
        reopened.runner.resume(id)
        reopened.await(done(id))
        assertFalse("готовый пробный срез не повторяется", 2 in reopened.engines.transcribed)
        assertEquals(11, reopened.engines.transcribed.size)
    }

    @Test
    fun diarizationResumesFromLastFinishedWindow() = runTest {
        val h = harness()
        h.engines.onWindow = { index ->
            if (index == 1) {
                h.die()
                throw CancellationException("смерть процесса")
            }
        }
        val id = h.import("diar")
        h.await { it[id]?.stage == Stage.DIARIZE }
        delay(1_000)
        assertEquals(listOf(0), DiarJournal(h.store.files(id).diar).restore().map { it.index })

        val reopened = Harness(this, folder.root)
        reopened.await { it[id]?.phase == JobPhase.INTERRUPTED }
        reopened.runner.resume(id)
        reopened.await(done(id))
        assertEquals(listOf(1), reopened.engines.restoredPerPass)
        assertEquals(listOf(1, 2), reopened.engines.diarizedWindows)
        assertTrue(reopened.engines.transcribed.isEmpty())
        assertEquals(listOf(0, 1, 2), DiarJournal(reopened.store.files(id).diar).restore().map { it.index })
        assertTrue(reopened.store.files(id).turns.exists())
    }

    @Test
    fun secondOpinionTurnsFileEnglish() = runTest {
        val h = harness()
        h.engines.secondOpinion = Language.EN
        val id = h.import("thin")
        h.await(done(id))
        assertEquals(Language.EN, h.runner.jobs.value.getValue(id).language)
    }

    @Test
    fun refinedTurnsReachMerge() = runTest {
        val h = harness()
        h.engines.refineBehavior = { turns -> turns + SpeakerTurn(0, 500, 1) }
        val id = h.import("refine")
        h.await(done(id))
        assertEquals(1, h.engines.refineRuns)
        assertTrue(h.engines.events.any { it.endsWith(":2") && it.startsWith("merge:") })
    }

    @Test
    fun refinerFailureKeepsDiarizationResult() = runTest {
        val h = harness()
        h.engines.refineBehavior = { error("сбой уточнения") }
        val id = h.import("refine-broken")
        h.await(done(id))
        assertEquals(1, h.engines.refineRuns)
        assertTrue(h.store.files(id).transcript.exists())
        assertTrue(h.engines.events.any { it.endsWith(":1") && it.startsWith("merge:") })
    }

    @Test
    fun refinerDoesNotRepeatAfterCrash() = runTest {
        val h = harness()
        h.engines.refineBehavior = { turns -> turns }
        val id = h.import("refine-once")
        h.store.files(id).dir.mkdirs()
        h.store.files(id).refineAttempt.writeText("1")
        h.await(done(id))
        assertEquals(0, h.engines.refineRuns)
    }

    @Test
    fun brokenWindowGetsTwoAttemptsAndIsSkipped() = runTest {
        val h = harness()
        h.engines.onWindow = { index -> if (index == 1) error("сбой на окне") }
        val id = h.import("window")
        h.await(done(id))
        val job = h.runner.jobs.value.getValue(id)
        assertEquals(listOf(1), job.skippedWindows)
        assertEquals(listOf(0, 2), h.engines.diarizedWindows)
        assertEquals(0, h.engines.openHeavy)
    }

    @Test
    fun codedDiarizerFailureIsFatalNotSkipped() = runTest {
        val h = harness()
        h.engines.onWindow = { throw EngineFailure(ErrorCode.MODEL_LOAD_FAILED, "нет файла модели") }
        val id = h.import("nomodel")
        h.await { it[id]?.phase == JobPhase.FAILED }
        val job = h.runner.jobs.value.getValue(id)
        assertEquals(ErrorCode.MODEL_LOAD_FAILED, job.errorCode)
        assertEquals(Stage.DIARIZE, job.stage)
        assertTrue(job.skippedWindows.isEmpty())
    }

    @Test
    fun cancelInsideDiarizationStopsBetweenWindows() = runTest {
        val h = harness()
        lateinit var id: String
        h.engines.onWindow = { index -> if (index == 1) h.runner.cancel(id) }
        id = h.import("cancel")
        h.await { it[id]?.phase == JobPhase.CANCELLED }
        assertEquals(listOf(0, 1), h.engines.diarizedWindows)
        assertEquals(0, h.engines.openHeavy)
    }

    @Test
    fun cancelStopsAtSegmentBoundaryAndReleasesEngines() = runTest {
        val h = harness()
        lateinit var id: String
        h.engines.onTranscribe = { index -> if (index == 2) h.runner.cancel(id) }
        id = h.import("cancel")
        h.await { it[id]?.phase == JobPhase.CANCELLED }

        assertEquals("после отмены срезов нет", 2, h.engines.transcribed.last())
        assertEquals(0, h.engines.openHeavy)
        assertTrue(h.outputs.transcripts.isEmpty())
        assertEquals("CANCELLED", h.statuses.last())
    }

    @Test
    fun cancelledJobRestartsFromCheckpoint() = runTest {
        val h = harness()
        lateinit var id: String
        var cancelOnce = true
        h.engines.onTranscribe = { index ->
            if (index == 2 && cancelOnce) {
                cancelOnce = false
                h.runner.cancel(id)
            }
        }
        id = h.import("again")
        h.await { it[id]?.phase == JobPhase.CANCELLED }
        h.runner.start(id)
        h.await(done(id))
        assertEquals(h.engines.transcribed.distinct(), h.engines.transcribed)
    }

    /** Декодер, который ловит отмену и выдаёт её за повреждённый файл, как это бывает с движками. */
    private fun Harness.decoderMisreadsCancellation() {
        engines.decodeBehavior = {
            try {
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                throw EngineFailure(ErrorCode.DECODE_CORRUPT, "Декодер остановился", cancelled)
            }
        }
    }

    @Test
    fun cancelDuringDecodingIsCancelNotCorruptFile() = runTest {
        val h = harness { decoderMisreadsCancellation() }
        val id = h.import("healthy")
        h.await { it[id]?.stage == Stage.DECODE && it[id]?.phase == JobPhase.RUNNING }
        delay(50)
        h.runner.cancel(id)
        h.await { it[id]?.phase == JobPhase.CANCELLED || it[id]?.phase == JobPhase.FAILED }
        val job = h.runner.jobs.value.getValue(id)
        assertEquals(JobPhase.CANCELLED, job.phase)
        assertNull(job.errorCode)
    }

    @Test
    fun serviceTimeoutDuringDecodingInterruptsNotFails() = runTest {
        val h = harness { decoderMisreadsCancellation() }
        val id = h.import("healthy")
        h.await { it[id]?.stage == Stage.DECODE && it[id]?.phase == JobPhase.RUNNING }
        delay(50)
        h.runner.interruptForTimeout()
        h.await { it[id]?.phase == JobPhase.INTERRUPTED || it[id]?.phase == JobPhase.FAILED }
        val job = h.runner.jobs.value.getValue(id)
        assertEquals(JobPhase.INTERRUPTED, job.phase)
        assertEquals(ErrorCode.FGS_TIMEOUT, job.errorCode)
        assertEquals(Stage.DECODE, job.stage)
    }

    @Test
    fun genuineDecoderFailureStaysAFailure() = runTest {
        val h = harness { engines.decodeBehavior = { throw EngineFailure(ErrorCode.DECODE_CORRUPT, "мусор") } }
        val id = h.import("broken")
        h.await { it[id]?.phase == JobPhase.FAILED }
        assertEquals(ErrorCode.DECODE_CORRUPT, h.runner.jobs.value.getValue(id).errorCode)
    }

    @Test
    fun cancelOfQueuedJobKeepsItFromRunning() = runTest {
        val h = harness()
        val first = h.import("first")
        val second = h.import("second")
        h.runner.cancel(second)
        h.await(done(first))
        assertEquals(JobPhase.CANCELLED, h.runner.jobs.value.getValue(second).phase)
        assertTrue(h.engines.events.none { it == "decode:$second" })
    }

    @Test
    fun thermalCriticalPausesAtBoundaryAndContinuesItself() = runTest {
        val h = harness()
        h.engines.onTranscribe = { index -> if (index == 1) h.device.thermal = 4 }
        val id = h.import("hot")
        h.await { it[id]?.phase == JobPhase.PAUSED }
        val paused = h.engines.transcribed.size
        delay(20_000)
        assertEquals(paused, h.engines.transcribed.size)
        assertEquals(JobPhase.PAUSED, h.runner.jobs.value.getValue(id).phase)

        h.device.thermal = 0
        h.await(done(id))
        assertEquals(5, h.engines.transcribed.size)
        assertTrue(ErrorCode.THERMAL_CRITICAL.name in h.runner.jobs.value.getValue(id).warnings)
    }

    @Test
    fun cancelDuringThermalPauseWorks() = runTest {
        val h = harness { device.thermal = 4 }
        val id = h.import("hot")
        h.await { it[id]?.phase == JobPhase.PAUSED }
        h.runner.cancel(id)
        h.await { it[id]?.phase == JobPhase.CANCELLED }
    }

    @Test
    fun lowMemoryWarnsButRunsCoreStagesAndSkipsLanguageModel() = runTest {
        val h = harness { device.memory = 300L * 1024 * 1024 }
        val id = h.import("tight")
        h.await(done(id))
        val job = h.runner.jobs.value.getValue(id)
        assertEquals(LlmStatus.SKIPPED_LOW_MEMORY, job.llm.status)
        assertEquals(ErrorCode.LLM_LOW_MEMORY, job.llm.errorCode)
        assertTrue(ErrorCode.LOW_MEMORY.name in job.warnings)
        assertTrue(h.engines.llmRuns.isEmpty())
        assertTrue(h.outputs.transcripts.containsKey("tight"))
    }

    @Test
    fun brokenSegmentGetsTwoAttemptsAndIsSkipped() = runTest {
        val h = harness()
        h.engines.onTranscribe = { index -> if (index == 2) error("сбой на срезе") }
        val id = h.import("broken")
        h.await(done(id))
        val job = h.runner.jobs.value.getValue(id)
        assertEquals(listOf(2), job.skippedSegments)
        assertEquals(setOf(0, 1, 3, 4), h.engines.transcribed.toSet())
        assertTrue("пробный срез упал", ErrorCode.LANG_ID_FAILED.name in job.warnings)
    }

    @Test
    fun invalidContractBlocksEveryFileAndFails() = runTest {
        val h = harness { engines.validation = ValidationResult.Invalid(listOf("плохой порядок")) }
        val id = h.import("bad")
        h.await { it[id]?.phase == JobPhase.FAILED }
        val job = h.runner.jobs.value.getValue(id)
        assertEquals(ErrorCode.CONTRACT_INVALID, job.errorCode)
        assertTrue(h.outputs.transcripts.isEmpty())
        assertFalse(h.store.files(id).transcript.exists())
        assertTrue(h.engines.llmRuns.isEmpty())
    }

    @Test
    fun silenceWritesEmptyContractAndSkipsLanguageModel() = runTest {
        val h = harness { engines.noSpeech = true }
        val id = h.import("silence")
        h.await(done(id))
        val job = h.runner.jobs.value.getValue(id)
        assertTrue(job.noSpeech)
        assertTrue(ErrorCode.AUDIO_EMPTY.name in job.warnings)
        val text = h.outputs.transcripts.getValue("silence").decodeToString()
        assertEquals("""{"transcript":[]}""", text.filterNot(Char::isWhitespace))
        assertTrue(h.engines.llmRuns.isEmpty())
        assertEquals("DONE", h.statuses.last())
        assertFalse(LlmRules.retryEligible(job))
    }

    @Test
    fun jsonBytesAreIdenticalWithAndWithoutLanguageModel() = runTest {
        val with = Harness(this, folder.newFolder("a"))
        val a = with.import("same")
        with.await(done(a))
        val without = Harness(this, folder.newFolder("b"), device = FakeDevice().apply { memory = 1 })
        val b = without.import("same")
        without.await(done(b))
        assertArrayEquals(
            with.outputs.transcripts.getValue("same"),
            without.outputs.transcripts.getValue("same"),
        )
        assertArrayEquals(
            with.store.files(a).transcript.readBytes(),
            with.outputs.transcripts.getValue("same"),
        )
    }

    @Test
    fun failedPublishKeepsTranscriptAndCompletesJob() = runTest {
        val h = harness { outputs.failPublish = true }
        val id = h.import("nofile")
        h.await(done(id))
        val output = checkNotNull(h.runner.jobs.value.getValue(id).output)
        assertTrue(output.exportFailed)
        assertTrue(h.store.files(id).transcript.exists())
        assertEquals(LlmStatus.APPLIED, h.runner.jobs.value.getValue(id).llm.status)
    }

    @Test
    fun takenOutputNameIsReportedWithRealPath() = runTest {
        val h = harness { outputs.nameTaken = true }
        val id = h.import("taken")
        h.await(done(id))
        val job = h.runner.jobs.value.getValue(id)
        assertEquals("Download/Skvor/taken (1).json", job.output?.path)
        assertTrue(OUTPUT_RENAMED_WARNING in job.warnings)
        assertTrue(OUTPUT_RENAMED_WARNING in h.store.files(id).metadata.readText())
    }

    @Test
    fun corruptFileFailsAtPreflightBeforeAnyModelAndLeavesNoCopy() = runTest {
        val h = harness { engines.probeFailure = EngineFailure(ErrorCode.DECODE_CORRUPT, "нет кадров") }
        val id = h.import("corrupt")
        h.await { it[id]?.phase == JobPhase.FAILED }
        val job = h.runner.jobs.value.getValue(id)
        assertEquals(ErrorCode.DECODE_CORRUPT, job.errorCode)
        assertEquals(Stage.IMPORT, job.stage)
        assertEquals(listOf("probe"), h.engines.events)
        assertTrue(h.store.files(id).dir.listFiles().orEmpty().none { it.name.startsWith("source") })
        assertEquals("FAILED:DECODE_CORRUPT", h.statuses.last())
    }

    @Test
    fun unreadableFileFailsWithItsOwnCode() = runTest {
        val h = harness {
            sources.describeFailure = EngineFailure(ErrorCode.IMPORT_NO_PERMISSION, "нет доступа")
        }
        val id = h.import("locked")
        h.await { it[id]?.phase == JobPhase.FAILED }
        assertEquals(ErrorCode.IMPORT_NO_PERMISSION, h.runner.jobs.value.getValue(id).errorCode)
        assertTrue(h.sources.copied.isEmpty())
    }

    @Test
    fun lowStorageSaysHowMuchIsMissingAndRetryRechecks() = runTest {
        val h = harness { sources.free = 1_000 }
        val id = h.import("big")
        h.await { it[id]?.phase == JobPhase.FAILED }
        val job = h.runner.jobs.value.getValue(id)
        assertEquals(ErrorCode.STORAGE_LOW, job.errorCode)
        val preflight = checkNotNull(job.preflight)
        assertTrue(PreflightRules.shortfallBytes(preflight.requiredBytes, preflight.freeBytes) > 0)
        assertTrue(h.engines.events.isEmpty())

        h.sources.free = 10_000_000_000
        h.runner.start(id)
        h.await(done(id))
    }

    @Test
    fun preflightIsVisibleBeforeProcessing() = runTest {
        val h = harness()
        val id = h.import("visible")
        h.await(done(id))
        val preflight = checkNotNull(h.runner.jobs.value.getValue(id).preflight)
        assertEquals(60_000L, preflight.durationMs)
        assertNotNull(preflight.estimateMinMs)
        assertFalse("первая запись идёт по примерной оценке", preflight.estimateMeasured)
    }

    @Test
    fun newImportCancelsRunningLanguageModelWithinASecond() = runTest {
        val h = harness()
        val held = CompletableDeferred<LlmResult>()
        h.engines.llmBehavior = { run -> if (run == 1) held.await() else LlmResult(LlmStatus.APPLIED, null, null) }
        val first = h.import("first")
        h.await { it[first]?.stage == Stage.LLM && it[first]?.phase == JobPhase.RUNNING }
        delay(100)

        val before = testScheduler.currentTime
        h.engines.onTranscribe = {}
        val second = h.import("second")
        held.complete(LlmResult(LlmStatus.CANCELLED_BY_NEW_JOB, "partial.json", null))
        h.await(done(first))

        assertTrue(testScheduler.currentTime - before < 1_000)
        assertEquals(1, h.engines.llmCancels)
        val job = h.runner.jobs.value.getValue(first)
        assertEquals(LlmStatus.DEFERRED, job.llm.status)
        assertFalse(job.llm.retryUsed)
        assertEquals("partial.json", job.llm.protocolPath)
        h.await(done(second))
    }

    @Test
    fun unresponsiveLanguageModelIsAbandonedAfterGrace() = runTest {
        val h = harness()
        h.engines.llmBehavior = { awaitCancellation() }
        val first = h.import("first")
        h.await { it[first]?.stage == Stage.LLM && it[first]?.phase == JobPhase.RUNNING }
        delay(100)
        val before = testScheduler.currentTime
        h.import("second")
        h.await(done(first))
        assertTrue(testScheduler.currentTime - before <= 1_000)
        assertEquals(LlmStatus.DEFERRED, h.runner.jobs.value.getValue(first).llm.status)
    }

    @Test
    fun deferredJobGetsOneRetryAfterThirtySecondsOfIdle() = runTest {
        val h = harness()
        val held = CompletableDeferred<LlmResult>()
        h.engines.llmBehavior = { run ->
            when (run) {
                1 -> held.await()
                else -> LlmResult(LlmStatus.APPLIED, "protocol.json", null)
            }
        }
        val first = h.import("first")
        h.await { it[first]?.phase == JobPhase.RUNNING && it[first]?.stage == Stage.LLM }
        delay(50)
        val second = h.import("second")
        held.complete(LlmResult(LlmStatus.CANCELLED_BY_NEW_JOB, null, null))
        h.await(done(second))
        val idleFrom = testScheduler.currentTime

        h.await { it[first]?.llm?.status == LlmStatus.APPLIED }
        assertTrue(testScheduler.currentTime - idleFrom >= 30_000)
        assertTrue(h.runner.jobs.value.getValue(first).llm.retryUsed)
        assertEquals(h.engines.llmRuns.first(), h.engines.llmRuns.last())
    }

    @Test
    fun cancellationByNewImportDoesNotSpendRetry() = runTest {
        val h = harness()
        val firstHold = CompletableDeferred<LlmResult>()
        val retryHold = CompletableDeferred<LlmResult>()
        h.engines.llmBehavior = { run ->
            when (run) {
                1 -> firstHold.await()
                2 -> LlmResult(LlmStatus.APPLIED, null, null)
                3 -> retryHold.await()
                else -> LlmResult(LlmStatus.APPLIED, "done.json", null)
            }
        }
        val first = h.import("first")
        h.await { it[first]?.phase == JobPhase.RUNNING && it[first]?.stage == Stage.LLM }
        delay(50)
        val second = h.import("second")
        firstHold.complete(LlmResult(LlmStatus.CANCELLED_BY_NEW_JOB, null, null))
        h.await(done(second))

        h.await { it[first]?.phase == JobPhase.RUNNING && it[first]?.stage == Stage.LLM }
        delay(50)
        val third = h.import("third")
        retryHold.complete(LlmResult(LlmStatus.CANCELLED_BY_NEW_JOB, null, null))
        h.await(done(first))
        assertEquals(LlmStatus.DEFERRED, h.runner.jobs.value.getValue(first).llm.status)
        assertFalse(h.runner.jobs.value.getValue(first).llm.retryUsed)

        h.await { it[third]?.phase == JobPhase.DONE }
        h.await { it[first]?.llm?.retryUsed == true }
        assertEquals(LlmStatus.APPLIED, h.runner.jobs.value.getValue(first).llm.status)
    }

    @Test
    fun importArrivingAsRetryStartsTakesPriorityOverRetry() = runTest {
        val h = harness()
        val first = h.import("first")
        h.await(done(first))
        h.runner.updateJob(first) { it.copy(llm = it.llm.copy(status = LlmStatus.DEFERRED)) }
        h.engines.llmRuns.clear()
        h.engines.llmBehavior = { awaitCancellation() }
        h.runner.onForeground()
        h.await { it[first]?.phase == JobPhase.RUNNING && it[first]?.stage == Stage.LLM }
        delay(50)
        val second = h.import("second")
        h.await { it[second]?.stage == Stage.DECODE || it[second]?.phase == JobPhase.RUNNING }
        h.await(done(first))
        assertEquals(LlmStatus.DEFERRED, h.runner.jobs.value.getValue(first).llm.status)
        assertFalse("отмена новым импортом не расходует повтор", h.runner.jobs.value.getValue(first).llm.retryUsed)
    }

    @Test
    fun budgetSkipGetsOneRetryThenIsFinal() = runTest {
        val h = harness {
            engines.llmBehavior = { LlmResult(LlmStatus.SKIPPED_BY_BUDGET, null, null) }
        }
        val id = h.import("slow")
        h.await { it[id]?.llm?.retryUsed == true && it[id]?.phase == JobPhase.DONE }
        delay(120_000)
        assertEquals(2, h.engines.llmRuns.size)
        assertEquals(LlmStatus.SKIPPED_BY_BUDGET, h.runner.jobs.value.getValue(id).llm.status)
    }

    @Test
    fun llmFailureNeverFailsJobOrTouchesJson() = runTest {
        val h = harness {
            engines.llmBehavior = { LlmResult(LlmStatus.FAILED, null, "LLM_DIED_OOM killed") }
        }
        val id = h.import("oom")
        h.await(done(id))
        val job = h.runner.jobs.value.getValue(id)
        assertEquals(LlmStatus.FAILED, job.llm.status)
        assertEquals(ErrorCode.LLM_DIED_OOM, job.llm.errorCode)
        assertEquals("DONE_LLM_FAILED", h.statuses.last())
        assertTrue(h.outputs.transcripts.containsKey("oom"))
    }

    @Test
    fun exceptionFromLanguageModelBecomesHonestStatus() = runTest {
        val h = harness { engines.llmBehavior = { error("процесс умер") } }
        val id = h.import("crash")
        h.await(done(id))
        assertEquals(LlmStatus.FAILED, h.runner.jobs.value.getValue(id).llm.status)
    }

    @Test
    fun waitsForModelCopyThenRunsLanguageModel() = runTest {
        val h = Harness(this, folder.root, gate = FakeGate(LlmModelState.PENDING))
        val id = h.import("waiting")
        h.await { it[id]?.phase == JobPhase.WAITING_MODEL }
        assertTrue("WAITING_MODEL" in h.statuses)
        assertTrue(h.outputs.transcripts.containsKey("waiting"))
        h.gate.current.value = LlmModelState.READY
        h.await(done(id))
        assertEquals(LlmStatus.APPLIED, h.runner.jobs.value.getValue(id).llm.status)
    }

    @Test
    fun newImportDuringModelWaitDefersProtocol() = runTest {
        val h = Harness(this, folder.root, gate = FakeGate(LlmModelState.PENDING))
        val first = h.import("first")
        h.await { it[first]?.phase == JobPhase.WAITING_MODEL }
        val second = h.import("second")
        h.await(done(first))
        assertEquals(LlmStatus.DEFERRED, h.runner.jobs.value.getValue(first).llm.status)
        assertEquals(0, h.engines.llmCancels)
        h.gate.current.value = LlmModelState.READY
        h.await(done(second))
    }

    @Test
    fun unavailableModelIsHonestAndNotRetried() = runTest {
        val h = Harness(this, folder.root, gate = FakeGate(LlmModelState.UNAVAILABLE))
        val id = h.import("nomodel")
        h.await(done(id))
        val job = h.runner.jobs.value.getValue(id)
        assertEquals(LlmStatus.FAILED, job.llm.status)
        assertEquals(ErrorCode.LLM_MODEL_UNAVAILABLE, job.llm.errorCode)
        assertTrue(h.engines.llmRuns.isEmpty())
    }

    @Test
    fun userCancelDuringLanguageModelIsFinal() = runTest {
        val h = harness { engines.llmBehavior = { awaitCancellation() } }
        val id = h.import("stop")
        h.await { it[id]?.phase == JobPhase.RUNNING && it[id]?.stage == Stage.LLM }
        delay(50)
        h.runner.cancel(id)
        h.await(done(id))
        val job = h.runner.jobs.value.getValue(id)
        assertEquals(LlmStatus.CANCELLED_BY_NEW_JOB, job.llm.status)
        assertFalse(LlmRules.retryEligible(job))
    }

    @Test
    fun modelCopyPausesOnlyDuringHeavyStages() = runTest {
        val h = harness()
        val id = h.import("gate")
        h.await(done(id))
        assertEquals(listOf(true, false), h.gate.busyLog)
    }

    @Test
    fun serviceTimeoutInterruptsAtBoundaryAndForegroundResumes() = runTest {
        val h = harness()
        h.engines.onTranscribe = { index -> if (index == 1) h.runner.interruptForTimeout() }
        val id = h.import("timeout")
        h.await { it[id]?.phase == JobPhase.INTERRUPTED }
        assertEquals(ErrorCode.FGS_TIMEOUT, h.runner.jobs.value.getValue(id).errorCode)
        assertEquals("FAILED:FGS_TIMEOUT", h.statuses.last())
        h.engines.onTranscribe = {}

        h.runner.onForeground()
        h.await(done(id))
        assertEquals(0, h.runner.jobs.value.getValue(id).deaths)
        assertEquals(1, h.engines.events.count { it.startsWith("decode:") })
    }

    @Test
    fun everyServiceTimeoutResumesByItselfWithoutSpendingTheCrashLimit() = runTest {
        val h = harness()
        var left = 3
        h.engines.onTranscribe = { if (left > 0) h.runner.interruptForTimeout() }
        val id = h.import("timeouts")
        while (left > 0) {
            h.await { it[id]?.phase == JobPhase.INTERRUPTED }
            left -= 1
            h.runner.onForeground()
            h.await { it[id]?.phase != JobPhase.INTERRUPTED }
        }
        h.await(done(id))
        assertEquals(0, h.runner.jobs.value.getValue(id).deaths)
    }

    @Test
    fun serviceTimeoutDuringImportIsAnInterruptionNotACancel() = runTest {
        val h = harness()
        h.sources.onCopy = { h.runner.interruptForTimeout() }
        val id = h.import("import")
        h.await { it[id]?.phase == JobPhase.INTERRUPTED || it[id]?.phase == JobPhase.CANCELLED }
        assertEquals(ErrorCode.FGS_TIMEOUT, h.runner.jobs.value.getValue(id).errorCode)
        h.sources.onCopy = {}

        h.runner.onForeground()
        h.await(done(id))
    }

    @Test
    fun largeFileIsReadInPlaceWithoutCopyAndWithoutSpaceForIt() = runTest {
        val size = 5_000_000_000
        val free = 2_000_000_000L
        val h = harness {
            sources.size = size
            sources.free = free
            sources.inPlace = InPlaceSource(TextInput("оригинал"), lasting = true)
        }
        val id = h.import("big")
        h.await(done(id))
        val job = h.runner.jobs.value.getValue(id)
        assertTrue(job.sourceInPlace)
        assertNull(job.sourceFile)
        assertTrue(h.sources.copied.isEmpty())
        assertEquals(size, job.preflight?.sizeBytes)
        assertTrue(checkNotNull(job.preflight).requiredBytes < free)
        assertTrue("decode:оригинал" in h.engines.events)
        assertTrue(h.store.files(id).dir.listFiles().orEmpty().none { it.name.startsWith("source") })
    }

    @Test
    fun smallFileIsCopiedEvenWhenItCouldBeReadInPlace() = runTest {
        val h = harness { sources.inPlace = InPlaceSource(TextInput("оригинал"), lasting = true) }
        val id = h.import("small")
        h.await(done(id))
        assertEquals("source.mp3", h.runner.jobs.value.getValue(id).sourceFile)
        assertEquals(listOf(id), h.sources.copied)
    }

    @Test
    fun largeFileWithTemporaryAccessIsCopiedWhenTheCopyFits() = runTest {
        val h = harness {
            sources.size = 1_000_000_000
            sources.inPlace = InPlaceSource(TextInput("оригинал"), lasting = false)
        }
        val id = h.import("temporary")
        h.await(done(id))
        assertEquals(listOf(id), h.sources.copied)
        assertFalse(h.runner.jobs.value.getValue(id).sourceInPlace)
    }

    @Test
    fun largeFileWithTemporaryAccessIsReadInPlaceWhenTheCopyDoesNotFit() = runTest {
        val h = harness {
            sources.size = 3_000_000_000
            sources.free = 2_000_000_000
            sources.inPlace = InPlaceSource(TextInput("оригинал"), lasting = false)
        }
        val id = h.import("tight")
        h.await(done(id))
        assertTrue(h.sources.copied.isEmpty())
        assertTrue(h.runner.jobs.value.getValue(id).sourceInPlace)
    }

    @Test
    fun largeFileThatCanNeitherFitNorBeSoughtReportsLowStorage() = runTest {
        val h = harness {
            sources.size = 3_000_000_000
            sources.free = 2_000_000_000
        }
        val id = h.import("pipe")
        h.await { it[id]?.phase == JobPhase.FAILED }
        assertEquals(ErrorCode.STORAGE_LOW, h.runner.jobs.value.getValue(id).errorCode)
    }

    @Test
    fun inPlaceFileThatNoLongerFitsTheWavStillReportsLowStorage() = runTest {
        val h = harness {
            sources.size = 5_000_000_000
            sources.free = 10_000_000
            sources.inPlace = InPlaceSource(TextInput("оригинал"), lasting = true)
        }
        val id = h.import("nospace")
        h.await { it[id]?.phase == JobPhase.FAILED }
        assertEquals(ErrorCode.STORAGE_LOW, h.runner.jobs.value.getValue(id).errorCode)
        assertTrue(h.engines.events.none { it.startsWith("decode:") })
    }

    @Test
    fun inPlaceJobResumesAfterDeathByReadingTheOriginalAgain() = runTest {
        val h = harness {
            sources.size = 5_000_000_000
            sources.inPlace = InPlaceSource(TextInput("оригинал"), lasting = true)
        }
        h.engines.decodeBehavior = { awaitCancellation() }
        val id = h.import("resume")
        h.await { it[id]?.stage == Stage.DECODE && it[id]?.phase == JobPhase.RUNNING }
        delay(50)
        h.die()

        val second = Harness(this, h.root, FakeEngines(), sources = h.sources)
        second.await { it[id]?.phase == JobPhase.INTERRUPTED }
        second.runner.onForeground()
        second.await(done(id))
        assertTrue("decode:оригинал" in second.engines.events)
    }

    @Test
    fun inPlaceJobFailsClearlyWhenTheOriginalIsGoneBeforeDecoding() = runTest {
        val h = harness {
            sources.size = 5_000_000_000
            sources.inPlace = InPlaceSource(TextInput("оригинал"), lasting = true)
        }
        h.engines.decodeBehavior = { awaitCancellation() }
        val id = h.import("gone")
        h.await { it[id]?.stage == Stage.DECODE && it[id]?.phase == JobPhase.RUNNING }
        delay(50)
        h.die()

        h.sources.inPlace = null
        val second = Harness(this, h.root, FakeEngines(), sources = h.sources)
        second.await { it[id]?.phase == JobPhase.INTERRUPTED }
        second.runner.onForeground()
        second.await { it[id]?.phase == JobPhase.FAILED }
        assertEquals(ErrorCode.IMPORT_READ_FAILED, second.runner.jobs.value.getValue(id).errorCode)
    }

    @Test
    fun repeatedDeathsStopAutoResumeAndWaitForButton() = runTest {
        val h = harness()
        h.engines.onTranscribe = { index ->
            if (index == 4) {
                h.die()
                throw CancellationException("смерть процесса")
            }
        }
        val id = h.import("killer")
        h.await { it[id]?.stage == Stage.ASR }
        delay(1_000)

        val second = Harness(this, folder.root)
        second.await { it[id]?.phase == JobPhase.INTERRUPTED }
        second.engines.onTranscribe = {
            second.die()
            throw CancellationException("смерть процесса")
        }
        second.runner.onForeground()
        second.await { it[id]?.phase == JobPhase.RUNNING && it[id]?.stage == Stage.ASR }
        delay(1_000)

        val third = Harness(this, folder.root)
        third.await { it[id]?.phase == JobPhase.INTERRUPTED }
        third.runner.onForeground()
        delay(5_000)
        assertEquals(JobPhase.INTERRUPTED, third.runner.jobs.value.getValue(id).phase)
        third.runner.resume(id)
        third.await(done(id))
    }

    /** Убивает процесс на распознавании и возвращает приложение после перезапуска. */
    private suspend fun TestScope.killedOnAsr(
        device: FakeDevice = FakeDevice(),
        config: RunnerConfig = RunnerConfig(),
    ): Pair<Harness, String> {
        val h = Harness(this, folder.root, engines = FakeEngines(segmentCount = 12))
        h.engines.onTranscribe = { index ->
            if (index == 6) {
                h.die()
                throw CancellationException("смерть процесса")
            }
        }
        val id = h.import("old")
        h.await { it[id]?.stage == Stage.ASR && it[id]?.phase == JobPhase.RUNNING }
        delay(1_000)
        val reopened = Harness(this, folder.root, engines = FakeEngines(segmentCount = 12), device = device, config = config)
        reopened.await { it[id]?.phase == JobPhase.INTERRUPTED }
        return reopened to id
    }

    @Test
    fun importAfterDeathStartsAtOnceAndOldJobNeverRunsAheadOfIt() = runTest {
        val (h, old) = killedOnAsr()
        h.runner.onForeground()
        val fresh = h.import("fresh")
        h.await(done(fresh))

        assertEquals(JobPhase.INTERRUPTED, h.runner.jobs.value.getValue(old).phase)
        assertEquals(listOf("decode:$fresh"), h.engines.events.filter { it.startsWith("decode:") })
        assertTrue(h.resumed.isEmpty())
    }

    @Test
    fun interruptedJobContinuesAloneOnlyAfterIdleOnOpenApp() = runTest {
        val idle = RunnerConfig().idleResumeMs
        val (h, old) = killedOnAsr()
        delay(idle * 2)
        assertEquals("закрытое приложение ничего не запускает", JobPhase.INTERRUPTED, h.runner.jobs.value.getValue(old).phase)

        h.runner.onForeground()
        delay(idle - 1_000)
        assertEquals(JobPhase.INTERRUPTED, h.runner.jobs.value.getValue(old).phase)
        h.await(done(old))
        assertEquals(1, h.runner.jobs.value.getValue(old).deaths)
        assertEquals(listOf(old), h.resumed)
    }

    @Test
    fun leavingTheAppRestartsTheIdleWait() = runTest {
        val idle = RunnerConfig().idleResumeMs
        val (h, old) = killedOnAsr()
        h.runner.onForeground()
        delay(idle - 1_000)
        h.runner.onBackground()
        delay(idle * 2)
        assertEquals(JobPhase.INTERRUPTED, h.runner.jobs.value.getValue(old).phase)
        h.runner.onForeground()
        delay(idle - 1_000)
        assertEquals(JobPhase.INTERRUPTED, h.runner.jobs.value.getValue(old).phase)
        h.await(done(old))
    }

    @Test
    fun forceStoppedJobWaitsForTheButtonEvenWhenIdle() = runTest {
        val (h, old) = killedOnAsr(device = FakeDevice().apply { stoppedByUser = true })
        assertTrue(h.runner.jobs.value.getValue(old).manualResume)
        h.runner.onForeground()
        delay(RunnerConfig().idleResumeMs * 3)
        assertEquals(JobPhase.INTERRUPTED, h.runner.jobs.value.getValue(old).phase)

        h.runner.resume(old)
        h.await(done(old))
    }

    @Test
    fun newImportPausesAutoResumedJobAtCheckpointAndItContinuesLater() = runTest {
        val (h, old) = killedOnAsr(config = RunnerConfig(idleResumeMs = 10_000))
        var fresh: String? = null
        h.engines.onTranscribe = { index -> if (index == 9 && fresh == null) fresh = h.import("fresh") }
        h.runner.onForeground()
        h.await { fresh != null && it[old]?.phase == JobPhase.INTERRUPTED }
        assertEquals(null, h.runner.jobs.value.getValue(old).errorCode)
        h.await(done(checkNotNull(fresh)))
        assertEquals(1, h.engines.peakHeavy)

        h.await(done(old))
        val decoded = h.engines.events.filter { it.startsWith("decode:") }
        assertEquals("старая задача не декодируется заново", listOf("decode:$fresh"), decoded)
        assertEquals("уступка не считается смертью", 1, h.runner.jobs.value.getValue(old).deaths)
        assertEquals(listOf(old, old), h.resumed)
        assertFalse("уступка не похожа на сбой", h.statuses.any { it.startsWith("FAILED") })
    }

    @Test
    fun jobGivingWayToNewImportStopsInsideVad() = runTest {
        val h = harness()
        var fresh: String? = null
        h.engines.onVadProgress = {
            if (fresh == null) {
                h.runner.onTaskRemoved()
                fresh = h.import("fresh")
            }
        }
        val id = h.import("old")
        h.await { it[id]?.phase == JobPhase.INTERRUPTED }
        assertEquals(Stage.VAD, h.runner.jobs.value.getValue(id).stage)
        h.await(done(checkNotNull(fresh)))
    }

    @Test
    fun appRemovedFromRecentsKeepsWorkingUntilNewImportArrives() = runTest {
        val h = harness()
        h.engines.decodeBehavior = { awaitCancellation() }
        val first = h.import("first")
        val second = h.import("second")
        h.await { it[first]?.stage == Stage.DECODE && it[first]?.phase == JobPhase.RUNNING }
        h.runner.onTaskRemoved()
        delay(1_000)
        assertEquals(JobPhase.RUNNING, h.runner.jobs.value.getValue(first).phase)

        h.engines.decodeBehavior = {}
        val fresh = h.import("fresh")
        h.await(done(fresh))
        assertEquals(JobPhase.INTERRUPTED, h.runner.jobs.value.getValue(first).phase)
        assertEquals(JobPhase.INTERRUPTED, h.runner.jobs.value.getValue(second).phase)
    }

    @Test
    fun jobResumedByButtonStandsBehindFilesAlreadyWaiting() = runTest {
        val (h, old) = killedOnAsr()
        h.runner.onForeground()
        h.engines.decodeBehavior = { awaitCancellation() }
        val first = h.import("first")
        val second = h.import("second")
        h.await { it[first]?.stage == Stage.DECODE && it[first]?.phase == JobPhase.RUNNING }
        h.runner.resume(old)
        h.await { it[old]?.phase == JobPhase.QUEUED }
        var recognizedBeforeSecond = -1
        h.engines.decodeBehavior = { recognizedBeforeSecond = h.engines.transcribed.size }
        h.runner.cancel(first)
        h.await(done(old))
        assertEquals(0, recognizedBeforeSecond)
    }

    @Test
    fun filesWithOneStemKeepSeparateOutputsAndRepeatKeepsTheName() = runTest {
        val h = harness()
        h.sources.name = "dialog.mp3"
        val first = h.import("dialog")
        h.await(done(first))
        h.sources.name = "dialog.wav"
        val second = h.import("dialog")
        h.await(done(second))
        h.sources.name = "dialog.mp3"
        val repeat = h.import("dialog")
        h.await(done(repeat))

        assertEquals("dialog", h.runner.jobs.value.getValue(first).stem)
        assertEquals("dialog.wav", h.runner.jobs.value.getValue(second).stem)
        assertEquals("dialog", h.runner.jobs.value.getValue(repeat).stem)
        assertEquals(setOf("dialog", "dialog.wav"), h.outputs.transcripts.keys)
        assertTrue(OUTPUT_RENAMED_WARNING in h.runner.jobs.value.getValue(second).warnings)
        assertFalse(OUTPUT_RENAMED_WARNING in h.runner.jobs.value.getValue(repeat).warnings)
    }

    @Test
    fun otherRecordingWithTheSameFileNameDoesNotOverwriteTheFirst() = runTest {
        val h = harness()
        h.sources.name = "dialog.mp3"
        val first = h.import("dialog")
        h.await(done(first))
        h.sources.size = 2_000
        val other = h.import("dialog")
        h.await(done(other))

        assertEquals("dialog", h.runner.jobs.value.getValue(first).stem)
        assertEquals("dialog.mp3", h.runner.jobs.value.getValue(other).stem)
        assertEquals(setOf("dialog", "dialog.mp3"), h.outputs.transcripts.keys)
        assertTrue(OUTPUT_RENAMED_WARNING in h.runner.jobs.value.getValue(other).warnings)
    }

    @Test
    fun abandonedJobGivesItsNameToTheNewFileAndTakesAnotherOnReturn() = runTest {
        val h = Harness(
            this,
            folder.root,
            engines = FakeEngines(segmentCount = 12),
            sources = FakeSources().apply { name = "dialog.wav" },
        )
        h.engines.onTranscribe = { index ->
            if (index == 3) {
                h.die()
                throw CancellationException("смерть процесса")
            }
        }
        val old = h.import("dialog")
        h.await { it[old]?.phase == JobPhase.RUNNING && it[old]?.stage == Stage.ASR }
        delay(1_000)
        val store = Harness(this, folder.root, sources = FakeSources().apply { name = "dialog.mp3" })
        store.await { it[old]?.phase == JobPhase.INTERRUPTED }

        val fresh = store.import("dialog")
        store.await(done(fresh))
        assertEquals("dialog", store.runner.jobs.value.getValue(fresh).stem)

        store.sources.name = "dialog.wav"
        store.runner.resume(old)
        store.await(done(old))
        assertEquals("dialog.wav", store.runner.jobs.value.getValue(old).stem)
        assertEquals(setOf("dialog", "dialog.wav"), store.outputs.transcripts.keys)
    }

    @Test
    fun pressingContinueLiftsTheBanOnResumingAlone() = runTest {
        val h = Harness(this, folder.root, engines = FakeEngines(segmentCount = 12))
        h.engines.onTranscribe = { index ->
            if (index == 3) {
                h.die()
                throw CancellationException("смерть процесса")
            }
        }
        val id = h.import("forced")
        h.await { it[id]?.stage == Stage.ASR && it[id]?.phase == JobPhase.RUNNING }
        delay(1_000)

        val closed = FakeDevice().apply { stoppedByUser = true }
        val reopened = Harness(this, folder.root, engines = FakeEngines(segmentCount = 12), device = closed)
        reopened.await { it[id]?.phase == JobPhase.INTERRUPTED }
        assertTrue(reopened.runner.jobs.value.getValue(id).manualResume)

        reopened.runner.resume(id)
        reopened.await(done(id))
        assertFalse(reopened.runner.jobs.value.getValue(id).manualResume)
    }

    @Test
    fun idleResumeDoesNotFireAfterTheAppLeftTheScreen() = runTest {
        val h = Harness(
            this,
            folder.root,
            engines = FakeEngines(segmentCount = 12),
            config = RunnerConfig(idleResumeMs = 1_000),
        )
        h.engines.onTranscribe = { index ->
            if (index == 3) {
                h.die()
                throw CancellationException("смерть процесса")
            }
        }
        val id = h.import("hidden")
        h.await { it[id]?.stage == Stage.ASR && it[id]?.phase == JobPhase.RUNNING }
        delay(1_000)

        val reopened = Harness(
            this,
            folder.root,
            engines = FakeEngines(segmentCount = 12),
            config = RunnerConfig(idleResumeMs = 1_000),
        )
        reopened.await { it[id]?.phase == JobPhase.INTERRUPTED }
        reopened.runner.onForeground()
        reopened.runner.onBackground()
        delay(10_000)
        assertEquals(JobPhase.INTERRUPTED, reopened.runner.jobs.value.getValue(id).phase)
        assertTrue(reopened.resumed.isEmpty())
    }

    @Test
    fun englishRecordingDropsRussianProbesAndUsesEnglishModel() = runTest {
        val h = harness { engines.language = Language.EN }
        val id = h.import("english")
        h.await(done(id))
        assertTrue("open:asr-RU" in h.engines.events)
        assertTrue("open:asr-EN" in h.engines.events)
        assertTrue(h.engines.events.indexOf("close:asr-RU") < h.engines.events.indexOf("open:asr-EN"))
        assertEquals(1, h.engines.peakHeavy)
        assertEquals(5, AsrJournal(h.store.files(id).asr).restore().size)
    }

    @Test
    fun settledLanguageReadsOnlyThreeProbes() = runTest {
        val h = harness { engines.language = Language.EN }
        val id = h.import("english")
        h.await(done(id))
        assertEquals(listOf(1, 2, 3, 0, 1, 2, 3, 4), h.engines.transcribed)
    }

    @Test
    fun unsettledLanguageReadsMoreProbes() = runTest {
        val h = harness {
            engines.language = Language.EN
            engines.languageSettled = false
        }
        val id = h.import("english")
        h.await(done(id))
        assertEquals(listOf(1, 2, 3, 0, 0, 1, 2, 3, 4), h.engines.transcribed)
    }

    @Test
    fun extraRussianProbesAreReusedInsteadOfRecognizedTwice() = runTest {
        val h = harness { engines.languageSettled = false }
        val id = h.import("russian")
        h.await(done(id))
        assertEquals(h.engines.transcribed.distinct(), h.engines.transcribed)
    }

    @Test
    fun russianProbesAreReusedInsteadOfRecognizedTwice() = runTest {
        val h = harness()
        val id = h.import("russian")
        h.await(done(id))
        assertEquals(h.engines.transcribed.distinct(), h.engines.transcribed)
        assertEquals(1, h.engines.events.count { it == "open:asr-RU" })
    }

    @Test
    fun recognizerLoadsWhenAppIsIdleAndAgainAfterJob() = runTest {
        val h = harness()
        h.runner.onForeground()
        delay(1_000)
        assertEquals(listOf("warm"), h.engines.events)

        val id = h.import("first")
        h.await(done(id))
        delay(1_000)
        assertEquals(1, h.engines.events.count { it == "take" })
        assertEquals(1, h.engines.events.count { it == "open:asr-RU" })
        assertEquals("warm", h.engines.events.last())
        assertTrue(h.engines.warm)
        assertEquals(0, h.engines.warmCollisions)

        val next = h.import("second")
        h.await(done(next))
        assertEquals(2, h.engines.events.count { it == "take" })
        assertEquals(0, h.engines.warmCollisions)
    }

    @Test
    fun recognizerStaysUnloadedUntilAppIsOnScreen() = runTest {
        val h = harness()
        delay(1_000)
        assertFalse("warm" in h.engines.events)
    }

    @Test
    fun jobLoadsMissingRecognizerBesideDecoding() = runTest {
        val h = harness()
        val id = h.import("direct")
        h.await(done(id))
        val warm = h.engines.events.indexOf("warm")
        val decode = h.engines.events.indexOfFirst { it.startsWith("decode") }
        assertTrue(warm in 0 until decode)
        assertEquals(1, h.engines.events.count { it == "open:asr-RU" })
    }

    @Test
    fun englishRecordingReleasesWaitingRecognizerBeforeItsModel() = runTest {
        val h = harness { engines.language = Language.EN }
        val id = h.import("english")
        h.await(done(id))
        assertEquals(1, h.engines.events.count { it == "open:asr-RU" })
        assertEquals(0, h.engines.warmCollisions)
    }

    @Test
    fun finishedRecognitionDoesNotLoadRecognizerAgain() = runTest {
        val h = harness()
        h.engines.onWindow = { index ->
            if (index == 1) {
                h.die()
                throw CancellationException("смерть процесса")
            }
        }
        val id = h.import("diar")
        h.await { it[id]?.stage == Stage.DIARIZE }
        delay(1_000)

        val reopened = Harness(this, folder.root)
        reopened.await { it[id]?.phase == JobPhase.INTERRUPTED }
        reopened.runner.resume(id)
        reopened.await(done(id))
        assertEquals(0, reopened.engines.warmCollisions)
        assertFalse("open:asr-RU" in reopened.engines.events)
    }

    @Test
    fun recognizerWaitingInIdleLeavesBeforeDiarizationOfResumedJob() = runTest {
        val h = harness()
        h.engines.onWindow = { index ->
            if (index == 1) {
                h.die()
                throw CancellationException("смерть процесса")
            }
        }
        val id = h.import("diar")
        h.await { it[id]?.stage == Stage.DIARIZE }
        delay(1_000)

        val reopened = Harness(this, folder.root)
        reopened.await { it[id]?.phase == JobPhase.INTERRUPTED }
        reopened.engines.warmUp()
        reopened.runner.resume(id)
        reopened.await(done(id))
        assertTrue("cool" in reopened.engines.events)
        assertEquals(0, reopened.engines.warmCollisions)
    }

    @Test
    fun recognizerWaitsForFileDuringRetryPauseAndLeavesBeforeRetry() = runTest {
        val h = harness()
        val held = CompletableDeferred<LlmResult>()
        h.engines.llmBehavior = { run ->
            if (run == 1) held.await() else LlmResult(LlmStatus.APPLIED, "protocol.json", null)
        }
        val first = h.import("first")
        h.await { it[first]?.phase == JobPhase.RUNNING && it[first]?.stage == Stage.LLM }
        delay(50)
        val second = h.import("second")
        held.complete(LlmResult(LlmStatus.CANCELLED_BY_NEW_JOB, null, null))
        h.await(done(second))
        h.await { it[first]?.llm?.status == LlmStatus.APPLIED }
        val retry = h.engines.events.lastIndexOf("llm")
        assertEquals(listOf("warm", "cool"), h.engines.events.subList(retry - 2, retry))
        assertEquals(0, h.engines.warmCollisions)
    }

    @Test
    fun deleteRemovesFilesAndKeepsExportUnlessAsked() = runTest {
        val h = harness()
        val keep = h.import("keep")
        h.await(done(keep))
        h.runner.delete(keep, removeExported = false)
        h.await { keep !in it }
        delay(10)
        assertFalse(h.store.files(keep).dir.exists())
        assertTrue(h.outputs.removed.isEmpty())

        val gone = h.import("gone")
        h.await(done(gone))
        h.runner.delete(gone, removeExported = true)
        h.await { gone !in it }
        delay(10)
        assertEquals(listOf("gone"), h.outputs.removed)
    }

    @Test
    fun metadataHoldsHashesAndNoTranscriptText() = runTest {
        val h = harness()
        val id = h.import("meta")
        h.await(done(id))
        val text = h.store.files(id).metadata.readText()
        assertTrue("asr-ru/encoder.int8.onnx" in text)
        assertTrue("rtf_core" in text)
        assertTrue("rtf_full" in text)
        assertFalse("слово" in text)
    }

    @Test
    fun timeBetweenStagesIsNotLostFromCoreTime() = runTest {
        val h = harness { device.thermal = 3 }
        val id = h.import("gap")
        h.await(done(id))
        val job = h.runner.jobs.value.getValue(id)
        assertTrue("накладные расходы: ${job.overheadMs}", job.overheadMs >= 200)
        assertEquals(job.stageMs.filterKeys { it != Stage.LLM }.values.sum() + job.overheadMs, job.coreMs)
    }

    @Test
    fun languageModelGetsStageLimits() = runTest {
        val h = harness()
        val id = h.import("budget")
        h.await(done(id))
        assertEquals(LlmBudgetPolicy.budget(), h.engines.llmRuns.single())
    }

    @Test
    fun doneAppearsOnlyAfterMetaFilesArePublished() = runTest {
        val h = harness()
        h.engines.llmBehavior = {
            h.store.files("job1").protocol.writeText("{}")
            LlmResult(LlmStatus.APPLIED, "protocol.json", null)
        }
        val id = h.import("order")
        h.await(done(id))
        val published = h.metaAtDone.single()
        assertTrue("order:metadata" in published)
        assertTrue("order:protocol" in published)
    }

    @Test
    fun metadataPublishedBeforeDoneCarriesFinalLanguageModelStatus() = runTest {
        val h = harness()
        val id = h.import("final")
        h.await(done(id))
        val text = h.store.files(id).metadata.readText()
        assertTrue(text, "APPLIED" in text)
    }

    @Test
    fun failedMetaPublishIsVisibleAndDoesNotTouchTheJson() = runTest {
        val h = harness()
        h.outputs.failMeta = true
        val id = h.import("sick")
        h.await(done(id))
        assertNotNull(h.outputs.transcripts["sick"])
        assertTrue(META_PUBLISH_FAILED in h.runner.jobs.value.getValue(id).warnings)
        assertEquals("DONE_META_FAILED", h.statuses.last())
    }

    @Test
    fun metaPublishRecoveredOnRetryClearsTheWarning() = runTest {
        val h = harness()
        h.outputs.failMeta = true
        h.engines.llmBehavior = { LlmResult(LlmStatus.DEFERRED, null, null) }
        val id = h.import("sick")
        h.await(done(id))
        assertEquals("DONE_META_FAILED", h.statuses.last())

        h.outputs.failMeta = false
        h.engines.llmBehavior = { LlmResult(LlmStatus.APPLIED, null, null) }
        h.runner.onForeground()
        h.await { it[id]?.llm?.retryUsed == true && META_PUBLISH_FAILED !in it.getValue(id).warnings }
    }
}
