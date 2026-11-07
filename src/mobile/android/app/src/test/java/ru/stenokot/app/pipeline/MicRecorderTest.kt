package ru.stenokot.app.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.stenokot.app.audio.MicSource
import ru.stenokot.app.audio.MicUnavailable
import ru.stenokot.app.audio.MicWavWriter
import ru.stenokot.app.data.JobRecord
import ru.stenokot.app.domain.MicProblem
import ru.stenokot.app.domain.RecordingUi
import java.io.File
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val RATE = 1_000

/** Выдаёт блоки, пока не попросят остановиться. Первый блок открывает [firstBlock]. */
private class EndlessMic(private val firstBlock: CountDownLatch) : MicSource {
    @Volatile
    private var stopped = false

    override val sampleRate = RATE

    override fun read(target: ShortArray): Int {
        if (stopped) return 0
        Thread.sleep(1)
        target.fill(1)
        firstBlock.countDown()
        return target.size
    }

    override fun silenced() = false

    override fun interrupt() {
        stopped = true
    }

    override fun close() = Unit
}

class MicRecorderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val imported = ArrayList<File>()
    private val jobs = HashMap<String, JobRecord>()
    private val firstBlock = CountDownLatch(1)

    private fun recorder(
        open: (Int?) -> MicSource = { EndlessMic(firstBlock) },
        startService: () -> Boolean = { true },
    ) = MicRecorder(
        root = { folder.root },
        openMic = open,
        startService = startService,
        import = { file ->
            imported += file
            "job-${imported.size}"
        },
        findJob = { jobs[it.name] },
        clock = { 1_794_057_303_000 },
        zone = { ZoneOffset.ofHours(3) },
    )

    private fun job(sourceFile: String?) = JobRecord(
        id = "a",
        seq = 1,
        title = "a",
        stem = "a",
        sourceUri = "file:///a",
        createdAtMs = 1,
        sourceFile = sourceFile,
    )

    private fun await(recorder: MicRecorder, done: (RecordingUi) -> Boolean): RecordingUi {
        repeat(2_000) {
            val state = recorder.state.value
            if (done(state)) return state
            Thread.sleep(2)
        }
        error("Состояние не дождались: ${recorder.state.value}")
    }

    private fun abandon(name: String, samples: Int): File {
        val file = folder.newFile(name)
        val writer = MicWavWriter(file, RATE)
        writer.write(ShortArray(samples), samples)
        writer.close()
        return file
    }

    @Test
    fun stoppedRecordingGoesToPipelineNamedByDate() {
        val recorder = recorder()
        recorder.requestStart()
        recorder.begin()
        assertTrue(firstBlock.await(5, TimeUnit.SECONDS))
        recorder.stop()

        assertEquals(RecordingUi.Saved("job-1"), await(recorder) { it is RecordingUi.Saved })
        assertEquals("Запись 2026-11-07 16-15-03.wav", imported.single().name)
        assertTrue(imported.single().length() > 44)
        assertEquals(listOf(imported.single()), folder.root.listFiles()!!.toList())

        recorder.acknowledge()
        assertEquals(RecordingUi.Idle, recorder.state.value)
    }

    @Test
    fun cancelDeletesEverythingAndImportsNothing() {
        val recorder = recorder()
        recorder.requestStart()
        recorder.begin()
        assertTrue(firstBlock.await(5, TimeUnit.SECONDS))
        recorder.cancel()

        await(recorder) { it == RecordingUi.Idle }
        assertTrue(imported.isEmpty())
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    @Test
    fun micThatDoesNotOpenIsReportedWithoutLeavingFiles() {
        val recorder = recorder(open = { throw MicUnavailable("нет") })
        recorder.requestStart()
        recorder.begin()

        assertEquals(RecordingUi.Failed(MicProblem.UNAVAILABLE), await(recorder) { it is RecordingUi.Failed })
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    @Test
    fun recordingWithoutAudioIsNotSentToPipeline() {
        val recorder = recorder(open = { firstBlockless() })
        recorder.requestStart()
        recorder.begin()
        await(recorder) { it is RecordingUi.Active }
        recorder.stop()

        assertEquals(RecordingUi.Failed(MicProblem.EMPTY), await(recorder) { it is RecordingUi.Failed })
        assertTrue(imported.isEmpty())
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    private fun firstBlockless(): MicSource = object : MicSource {
        @Volatile
        private var stopped = false

        override val sampleRate = RATE

        override fun read(target: ShortArray): Int {
            while (!stopped) Thread.sleep(1)
            return 0
        }

        override fun silenced() = false

        override fun interrupt() {
            stopped = true
        }

        override fun close() = Unit
    }

    @Test
    fun refusedServiceIsReported() {
        val recorder = recorder(startService = { false })
        recorder.requestStart()
        assertEquals(RecordingUi.Failed(MicProblem.UNAVAILABLE), recorder.state.value)
    }

    @Test
    fun secondStartDoesNothingWhileRecording() {
        var services = 0
        val recorder = recorder(startService = { services += 1; true })
        recorder.requestStart()
        recorder.requestStart()
        assertEquals(1, services)
    }

    @Test
    fun beginWithoutRequestDoesNothing() {
        val recorder = recorder()
        recorder.begin()
        assertEquals(RecordingUi.Idle, recorder.state.value)
    }

    @Test
    fun recordingCutShortByProcessDeathIsRepairedAndImported() {
        abandon("Запись 1.wav.part", 500)
        recorder().recover()

        val file = imported.single()
        assertEquals("Запись 1.wav", file.name)
        assertTrue(file.exists())
        assertFalse(File(folder.root, "Запись 1.wav.part").exists())
    }

    @Test
    fun recordingWithoutAudioIsDroppedOnRecovery() {
        abandon("Запись 1.wav.part", 0)
        recorder().recover()

        assertTrue(imported.isEmpty())
        assertEquals(0, folder.root.listFiles()!!.size)
    }

    @Test
    fun fileLostBetweenStopAndImportStillReachesPipeline() {
        abandon("Запись 2.wav", 100)
        recorder().recover()
        assertEquals("Запись 2.wav", imported.single().name)
    }

    @Test
    fun recoveryKeepsSourceOfUnfinishedImportAndDropsCopiedOne() {
        val waiting = abandon("Запись 3.wav", 100)
        val copied = abandon("Запись 4.wav", 100)
        jobs[waiting.name] = job(sourceFile = null)
        jobs[copied.name] = job(sourceFile = "source.wav")

        recorder().recover()

        assertTrue(imported.isEmpty())
        assertTrue(waiting.exists())
        assertFalse(copied.exists())
    }

    @Test
    fun emptyDirectoryIsFine() {
        folder.delete()
        recorder().recover()
        assertTrue(imported.isEmpty())
        assertNull(folder.root.listFiles())
    }
}
