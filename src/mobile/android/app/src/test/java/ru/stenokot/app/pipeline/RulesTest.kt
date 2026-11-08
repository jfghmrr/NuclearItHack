package ru.stenokot.app.pipeline

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.stenokot.app.data.JobPhase
import ru.stenokot.app.data.JobRecord
import ru.stenokot.app.data.LlmRecord
import ru.stenokot.core.metrics.DeviceSpeed
import ru.stenokot.core.model.LlmResult
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.SpeechSegment
import ru.stenokot.core.model.Stage
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/** Чистые правила: очередь, повтор, имена, копия модели и предварительная проверка. */
class RulesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun job(id: String, seq: Long, phase: JobPhase = JobPhase.QUEUED, llm: LlmRecord = LlmRecord()) = JobRecord(
        id = id,
        seq = seq,
        title = "$id.mp3",
        stem = id,
        sourceUri = "content://$id",
        createdAtMs = seq,
        phase = phase,
        llm = llm,
    )

    @Test
    fun queueOrderIsArrivalOrderNotMapOrder() {
        val jobs = listOf(job("c", 3), job("a", 1), job("b", 2))
        assertEquals("a", JobQueue.next(jobs)?.id)
        assertEquals(listOf("a", "b", "c"), JobQueue.waiting(jobs).map { it.id })
        assertEquals(2, JobQueue.position(jobs, "b"))
        assertNull(JobQueue.position(jobs, "z"))
    }

    @Test
    fun oldestEligibleJobGetsRetryFirst() {
        val jobs = listOf(
            job("used", 1, JobPhase.DONE, LlmRecord(LlmStatus.DEFERRED, retryUsed = true)),
            job("young", 3, JobPhase.DONE, LlmRecord(LlmStatus.SKIPPED_BY_BUDGET)),
            job("old", 2, JobPhase.DONE, LlmRecord(LlmStatus.DEFERRED)),
            job("fine", 0, JobPhase.DONE, LlmRecord(LlmStatus.APPLIED)),
        )
        assertEquals("old", JobQueue.nextRetry(jobs)?.id)
    }

    @Test
    fun onlyDeferredAndBudgetSkipAreRetried() {
        for (status in LlmStatus.entries) {
            val eligible = LlmRules.retryEligible(job("x", 1, JobPhase.DONE, LlmRecord(status)))
            val expected = status == LlmStatus.DEFERRED || status == LlmStatus.SKIPPED_BY_BUDGET
            assertEquals(status.name, expected, eligible)
        }
    }

    @Test
    fun settleSpendsRetryOnlyWhenItActuallyRan() {
        val finished = LlmRules.settle(LlmRecord(), LlmResult(LlmStatus.APPLIED, "p", null), isRetry = true)
        assertTrue(finished.retryUsed)
        val limit = LlmRules.settle(LlmRecord(), LlmResult(LlmStatus.SKIPPED_BY_BUDGET, null, null), isRetry = true)
        assertTrue(limit.retryUsed)
        val preempted = LlmRules.settle(LlmRecord(), LlmResult(LlmStatus.CANCELLED_BY_NEW_JOB, null, null), isRetry = true)
        assertFalse(preempted.retryUsed)
        assertEquals(LlmStatus.DEFERRED, preempted.status)
        val first = LlmRules.settle(LlmRecord(), LlmResult(LlmStatus.SKIPPED_BY_BUDGET, null, null), isRetry = false)
        assertFalse(first.retryUsed)
    }

    @Test
    fun partialProtocolSurvivesLaterRunWithoutOne() {
        val prior = LlmRecord(protocolPath = "partial.json")
        val settled = LlmRules.settle(prior, LlmResult(LlmStatus.FAILED, null, "сбой"), isRetry = false)
        assertEquals("partial.json", settled.protocolPath)
    }

    @Test
    fun stemKeepsCyrillicAndStripsPathCharacters() {
        assertEquals("Заседание от 5 ноября", StemRules.stem("Заседание от 5 ноября.mp3"))
        assertEquals("a_b", StemRules.stem("a:b.wav"))
        assertEquals("audio", StemRules.stem(".mp3"))
        assertEquals("multi.dot.name", StemRules.stem("multi.dot.name.m4a"))
        assertEquals("c", StemRules.stem("/a/b/c.flac"))
    }

    @Test
    fun stemClaimKeepsFirstFileExactAndSeparatesOtherExtensions() {
        assertEquals("dialog1", StemRules.claim("dialog1.mp3", emptyList()))
        val first = StemClaim("dialog1.mp3", "dialog1")
        assertEquals("dialog1", StemRules.claim("dialog1.mp3", listOf(first)))
        assertEquals("dialog1", StemRules.claim("DIALOG1.MP3", listOf(first)))
        assertEquals("dialog1.wav", StemRules.claim("dialog1.wav", listOf(first)))
        assertEquals("Dialog1.wav", StemRules.claim("Dialog1.wav", listOf(first)))
        val second = StemClaim("dialog1.wav", "dialog1.wav")
        assertEquals("dialog1.wav", StemRules.claim("dialog1.wav", listOf(first, second)))
        assertEquals("dialog1.wav.mp3", StemRules.claim("dialog1.wav.mp3", listOf(second)))
        val taken = listOf(StemClaim("a.mp3", "a"), StemClaim("b.wav", "a.wav"))
        assertEquals("a.wav (2)", StemRules.claim("a.wav", taken))
    }

    @Test
    fun stemClaimTellsFilesApartByNameAndSize() {
        val first = StemClaim("dialog1.mp3", "dialog1", 1_000)
        assertEquals("dialog1", StemRules.claim("dialog1.mp3", listOf(first), 1_000))
        assertEquals("dialog1", StemRules.claim("dialog1.mp3", listOf(first), 0))
        assertEquals("dialog1.mp3", StemRules.claim("dialog1.mp3", listOf(first), 2_000))
        val second = StemClaim("dialog1.mp3", "dialog1.mp3", 2_000)
        assertEquals("dialog1.mp3 (2)", StemRules.claim("dialog1.mp3", listOf(first, second), 3_000))
    }

    @Test
    fun extensionIsLowercaseAndRejectsNonsense() {
        assertEquals("mp3", StemRules.extension("X.MP3"))
        assertEquals("", StemRules.extension("noext"))
        assertEquals("", StemRules.extension("a.not an extension"))
    }

    @Test
    fun titleComesFromLastPartOfDocumentId() {
        assertEquals("x.mp3", StemRules.titleFromUri("primary:Download/x.mp3"))
        assertEquals("1234", StemRules.titleFromUri("audio:1234"))
        assertEquals("Аудио", StemRules.titleFromUri(null))
    }

    @Test
    fun probesComeFromDifferentPlaces() {
        val segments = List(100) { SpeechSegment(it, it * 10L, it * 10L + 5) }
        assertEquals(listOf(24, 49, 74), LanguageProbes.pick(segments).map { it.index })
        assertEquals(2, LanguageProbes.pick(segments.take(2)).size)
        assertTrue(LanguageProbes.pick(emptyList()).isEmpty())
        assertEquals(listOf(12, 37, 61, 86), LanguageProbes.extra(segments).map { it.index })
        assertEquals(listOf(0), LanguageProbes.extra(segments.take(5)).map { it.index })
        assertTrue(LanguageProbes.extra(segments.take(3)).isEmpty())
        assertTrue(LanguageProbes.extra(emptyList()).isEmpty())
    }

    @Test
    fun requiredBytesGrowWithDurationAndUnknownDurationStillNeedsTheCopy() {
        val short = PreflightRules.requiredBytes(1_000, 60_000)
        val long = PreflightRules.requiredBytes(1_000, 3_600_000)
        assertTrue(long > short)
        assertTrue(PreflightRules.requiredBytes(1_000, null) > 1_000)
        assertEquals(0, PreflightRules.shortfallBytes(100, 200))
        assertEquals(50, PreflightRules.shortfallBytes(250, 200))
    }

    @Test
    fun eightHourFileReadInPlaceNeedsNoRoomForTheFileItself() {
        val eightHours = 8L * 3600 * 1000
        val fiveGigabytes = 5_000_000_000L
        val wavBytes = 8L * 3600 * 32_000
        val inPlace = PreflightRules.requiredBytes(0, eightHours)
        assertTrue(inPlace >= wavBytes)
        assertTrue(inPlace < 2_000_000_000L)
        assertEquals(fiveGigabytes, PreflightRules.requiredBytes(fiveGigabytes, eightHours) - inPlace)
    }

    @Test
    fun onlyLargeFilesAreReadInPlaceAndCopyRemainsTheSaferChoice() {
        val limit = PreflightRules.COPY_LIMIT_BYTES
        val roomy = 100_000_000_000L
        assertFalse(PreflightRules.readsInPlace(limit, lasting = true, freeBytes = roomy))
        assertTrue(PreflightRules.readsInPlace(limit + 1, lasting = true, freeBytes = roomy))
        assertFalse(PreflightRules.readsInPlace(limit + 1, lasting = false, freeBytes = roomy))
        assertTrue(PreflightRules.readsInPlace(limit + 1, lasting = false, freeBytes = limit))
    }

    @Test
    fun estimateIsHiddenWithoutDurationAndLabelledRoughWithoutMeasurement() {
        val unknown = PreflightRules.record("x", 1, null, null, 10, null)
        assertNull(unknown.estimateMinMs)
        assertFalse(unknown.estimateMeasured)

        val rough = PreflightRules.record("x", 1, 600_000, null, 10, null)
        assertNotNull(rough.estimateMinMs)
        assertFalse(rough.estimateMeasured)

        val speed = DeviceSpeed(mapOf(Stage.ASR to 0.1, Stage.DIARIZE to 0.2))
        val measured = PreflightRules.record("x", 1, 600_000, null, 10, speed)
        assertTrue(measured.estimateMeasured)
    }

    @Test
    fun ledgerReadsLockAndGivesStableVersion() {
        val lock = "aa11  asr-ru/encoder.int8.onnx|bb22  llm/qwen3-0.6b-q4_k_m.gguf"
        val ledger = ModelLedger(lock)
        assertEquals("aa11", ledger.sha256Of("asr-ru/encoder.int8.onnx"))
        assertEquals("bb22", ledger.sha256Of(ModelLedger.LLM_PATH))
        assertNull(ledger.sha256Of("нет"))
        assertEquals(12, ledger.version.length)
        assertEquals(ledger.version, ModelLedger(lock).version)
        assertEquals(2, ledger.modelHashes { 7 }.size)
    }

    @Test
    fun ledgerParsesRealLockFile() {
        val lock = File(System.getProperty("user.dir"), "../../../../config/models.lock")
            .takeIf { it.exists() } ?: File("../../../../config/models.lock")
        val ledger = ModelLedger(lock.readText())
        assertEquals(12, ledger.paths.size)
        assertNotNull(ledger.sha256Of(ModelLedger.LLM_PATH))
        for (card in ModelLedger.Cards) {
            assertNotNull(card.name, ledger.sha256Of(card.hashPath))
        }
    }

    @Test
    fun gateLimitsToOncePerSecondButLetsForcedThrough() {
        var now = 0L
        val gate = ProgressGate { now }
        assertTrue(gate.allow())
        now = 400
        assertFalse(gate.allow())
        assertTrue(gate.allow(force = true))
        now = 1_399
        assertFalse(gate.allow())
        now = 1_400
        assertTrue(gate.allow())
    }

    @Test
    fun boardNeverPublishesValuesFasterThanOncePerSecond() {
        var now = 0L
        val board = LiveBoard { now }
        board.enter("a", Stage.ASR, 0)
        val seen = LinkedHashSet<Float?>()
        for (step in 1..50) {
            now += 100
            board.progress(step / 50f)
            seen += board.flow.value?.fraction
        }
        assertTrue("показано ${seen.size}", seen.size <= 6)
    }

    @Test
    fun enteringStageShowsImmediatelyAndKeepsCancelling() {
        var now = 0L
        val board = LiveBoard { now }
        board.enter("a", Stage.ASR, 0)
        board.markCancelling("a")
        now = 10
        board.enter("a", Stage.DIARIZE, 5)
        assertEquals(Stage.DIARIZE, board.flow.value?.stage)
        assertTrue(checkNotNull(board.flow.value).cancelling)
    }

    @Test
    fun noteCarriesRealCounters() {
        var now = 0L
        val board = LiveBoard { now }
        board.enter("a", Stage.LLM, 0)
        now = 2_000
        board.report(0.5f, "3/8")
        assertEquals(3, board.flow.value?.counterDone)
        assertEquals(8, board.flow.value?.counterTotal)
    }

    @Test
    fun intentImportsOnlyOnFirstCreate() {
        assertTrue(IntentRules.shouldImport(restored = false, fromHistory = false))
        assertFalse(IntentRules.shouldImport(restored = true, fromHistory = false))
        assertFalse(IntentRules.shouldImport(restored = false, fromHistory = true))
    }

    private fun copy(data: ByteArray, tmp: File, target: File, expected: String?, before: suspend () -> Unit = {}) =
        ResumableCopy({ ByteArrayInputStream(data) }, data.size.toLong(), tmp, target, expected, bufferBytes = 8)

    private fun sha(data: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    @Test
    fun fileHashNoticesOneChangedByteAndYieldsBetweenBlocks() = runBlocking {
        val data = ByteArray(1_000) { it.toByte() }
        val file = File(folder.root, "m.gguf").apply { writeBytes(data) }
        var pauses = 0
        assertEquals(sha(data), sha256Of(file, beforeBlock = { pauses += 1 }, bufferBytes = 8))
        assertEquals(1_000 / 8 + 1, pauses)
        val damaged = data.copyOf().also { it[500] = (it[500] + 1).toByte() }
        file.writeBytes(damaged)
        assertFalse(sha(data) == sha256Of(file, beforeBlock = {}))
    }

    @Test
    fun copyVerifiesHashAndPublishesAtomically() = runBlocking {
        val data = ByteArray(1_000) { it.toByte() }
        val tmp = File(folder.root, "m.tmp")
        val target = File(folder.root, "sub/m.gguf")
        var last = 0L
        val ok = copy(data, tmp, target, sha(data)).run({}, { last = it })
        assertTrue(ok)
        assertEquals(1_000L, last)
        assertTrue(target.readBytes().contentEquals(data))
        assertFalse(tmp.exists())
    }

    @Test
    fun copyResumesFromPartialFileAndStillHashesWholeFile() = runBlocking {
        val data = ByteArray(1_000) { (it * 7).toByte() }
        val tmp = File(folder.root, "m.tmp")
        tmp.writeBytes(data.copyOf(400))
        val target = File(folder.root, "m.gguf")
        var blocks = 0
        val ok = copy(data, tmp, target, sha(data)).run({ blocks += 1 }, {})
        assertTrue(ok)
        assertTrue(target.readBytes().contentEquals(data))
        assertTrue("дописан только хвост: $blocks", blocks <= 600 / 8 + 2)
    }

    @Test
    fun corruptedPartialFileFailsHashAndIsNotPublished() = runBlocking {
        val data = ByteArray(1_000) { it.toByte() }
        val tmp = File(folder.root, "m.tmp")
        tmp.writeBytes(ByteArray(400))
        val target = File(folder.root, "m.gguf")
        assertFalse(copy(data, tmp, target, sha(data)).run({}, {}))
        assertFalse(target.exists())
        assertFalse("плохой хвост не должен переживать проверку", tmp.exists())
    }

    @Test
    fun copyStopsBeforeEveryBlockWhilePaused() = runBlocking {
        val data = ByteArray(64) { it.toByte() }
        val tmp = File(folder.root, "m.tmp")
        val target = File(folder.root, "m.gguf")
        val waits = ArrayList<Long>()
        copy(data, tmp, target, null).run({ waits += tmp.length() }, {})
        assertEquals(listOf(0L, 8L, 16L, 24L, 32L, 40L, 48L, 56L, 64L), waits)
    }

    @Test
    fun externalSourceCannotPointIntoPrivateFiles() {
        val roots = listOf("/data/user/0/ru.skvor.app", "/data/data/ru.skvor.app")
        val pkg = "ru.skvor.app"
        assertTrue(IntentRules.allowedSource("file", "/storage/emulated/0/Download/a.mp3", null, roots, pkg))
        assertTrue(IntentRules.allowedSource(null, "/sdcard/Download/a.mp3", null, roots, pkg))
        assertFalse(IntentRules.allowedSource("file", "/data/user/0/ru.skvor.app/files/jobs/x/source.wav", null, roots, pkg))
        assertFalse(IntentRules.allowedSource("file", "/data/data/ru.skvor.app", null, roots, pkg))
        assertTrue(IntentRules.allowedSource("file", "/data/user/0/ru.skvor.application/a.mp3", null, roots, pkg))
        assertFalse(IntentRules.allowedSource("file", null, null, roots, pkg))
        assertTrue(IntentRules.allowedSource("content", "/x", "com.android.providers.downloads.documents", roots, pkg))
        assertFalse(IntentRules.allowedSource("content", "/x", "ru.skvor.app.fileprovider", roots, pkg))
        assertFalse(IntentRules.allowedSource("content", "/x", "ru.skvor.app", roots, pkg))
        assertFalse(IntentRules.allowedSource("http", "/a.mp3", "example.com", roots, pkg))
    }
}
