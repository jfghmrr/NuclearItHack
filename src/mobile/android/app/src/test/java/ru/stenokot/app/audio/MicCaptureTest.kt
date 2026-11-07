package ru.stenokot.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneOffset

private const val RATE = 1_000
private const val BLOCK = RATE / 10

/** Один шаг сценария поддельного микрофона. */
private sealed interface Step {
    data class Samples(val value: Short) : Step
    data class Silenced(val value: Short) : Step
    data object Failure : Step
    data object Nothing : Step
}

/** Микрофон по сценарию. Когда сценарий кончился, просит цикл остановиться через [onEnd]. */
private class ScriptedMic(private val steps: ArrayDeque<Step>, private val onEnd: () -> Unit) : MicSource {
    var closed = false
    private var silenced = false

    override val sampleRate = RATE

    override fun read(target: ShortArray): Int {
        val step = steps.removeFirstOrNull()
        if (step == null) {
            onEnd()
            return 0
        }
        val value = when (step) {
            is Step.Samples -> step.value.also { silenced = false }
            is Step.Silenced -> step.value.also { silenced = true }
            Step.Failure -> return AUDIO_ERROR
            Step.Nothing -> return 0
        }
        target.fill(value)
        return target.size
    }

    override fun silenced() = silenced

    override fun interrupt() = Unit

    override fun close() {
        closed = true
    }

    private companion object {
        const val AUDIO_ERROR = -6
    }
}

class MicCaptureTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val progress = ArrayList<MicProgress>()

    private fun data(file: File): ShortArray {
        val bytes = file.readBytes()
        val shorts = ByteBuffer.wrap(bytes, WAV_HEADER_BYTES, bytes.size - WAV_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(shorts.remaining()).also { shorts.get(it) }
    }

    private fun capture(
        steps: List<Step>,
        file: File,
        free: () -> Long = { Long.MAX_VALUE },
        reopen: (Int?) -> MicSource = { throw MicUnavailable("нет") },
    ): MicResult {
        lateinit var session: MicCapture
        var opened = false
        session = MicCapture(
            open = { rate ->
                if (opened) {
                    reopen(rate)
                } else {
                    opened = true
                    ScriptedMic(ArrayDeque(steps)) { session.stop() }
                }
            },
            freeBytes = free,
            onProgress = progress::add,
            pause = {},
        )
        return session.run(file)
    }

    @Test
    fun everySampleReachesTheFileInOrder() {
        val file = folder.newFile("a.wav")
        val result = capture(listOf(Step.Samples(1), Step.Samples(2), Step.Samples(3)), file)
        assertEquals(MicEnd.STOPPED, result.end)
        assertEquals(3L * BLOCK, result.samples)
        val values = data(file)
        assertEquals(3 * BLOCK, values.size)
        assertEquals(1, values[0].toInt())
        assertEquals(2, values[BLOCK].toInt())
        assertEquals(3, values[2 * BLOCK].toInt())
    }

    @Test
    fun finishedFileHasRealSizesInHeader() {
        val file = folder.newFile("a.wav")
        capture(listOf(Step.Samples(7)), file)
        val head = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(RATE, head.getInt(24))
        assertEquals(BLOCK * 2, head.getInt(40))
        assertEquals(WAV_HEADER_BYTES + BLOCK * 2 - 8, head.getInt(4))
    }

    @Test
    fun silenceWhileMicIsTakenIsNotWrittenAndRecordingContinues() {
        val file = folder.newFile("a.wav")
        val steps = buildList {
            add(Step.Samples(1))
            repeat(9) { add(Step.Samples(1)) }
            repeat(10) { add(Step.Silenced(0)) }
            repeat(10) { add(Step.Samples(5)) }
        }
        val result = capture(steps, file)
        assertEquals(20L * BLOCK, result.samples)
        assertTrue(progress.any { it.busy })
        assertFalse(progress.last().busy)
        assertEquals(5, data(file).last().toInt())
    }

    @Test
    fun micFailureReopensAtSameRateAndKeepsWhatWasRecorded() {
        val file = folder.newFile("a.wav")
        lateinit var session: MicCapture
        var opens = 0
        var requested: Int? = -1
        session = MicCapture(
            open = { rate ->
                opens += 1
                if (opens == 1) {
                    ScriptedMic(ArrayDeque(listOf(Step.Samples(1), Step.Failure))) { session.stop() }
                } else {
                    requested = rate
                    ScriptedMic(ArrayDeque(listOf(Step.Samples(2)))) { session.stop() }
                }
            },
            freeBytes = { Long.MAX_VALUE },
            onProgress = progress::add,
            pause = {},
        )
        val result = session.run(file)
        assertEquals(MicEnd.STOPPED, result.end)
        assertEquals(RATE, requested)
        assertEquals(2L * BLOCK, result.samples)
        assertTrue(progress.any { it.busy })
    }

    @Test
    fun lostMicEndsRecordingWithFileIntact() {
        val file = folder.newFile("a.wav")
        val result = capture(listOf(Step.Samples(1), Step.Failure), file)
        assertEquals(MicEnd.MIC_LOST, result.end)
        assertEquals(BLOCK.toLong(), result.samples)
        assertEquals(BLOCK, data(file).size)
    }

    @Test
    fun emptyReadsDoNotSpinOrWriteAnything() {
        val file = folder.newFile("a.wav")
        val result = capture(listOf(Step.Nothing, Step.Nothing, Step.Samples(1)), file)
        assertEquals(BLOCK.toLong(), result.samples)
    }

    @Test
    fun runningOutOfRoomStopsWithFileIntact() {
        val file = folder.newFile("a.wav")
        var checks = 0
        val free = { if (checks++ == 0) Long.MAX_VALUE else 1L }
        val result = capture(List(30) { Step.Samples(1) }, file, free)
        assertEquals(MicEnd.NO_SPACE, result.end)
        assertEquals(10L * BLOCK, result.samples)
    }

    @Test
    fun fullDiskIsReportedBeforeTheMicOpens() {
        val file = folder.newFile("a.wav")
        val session = MicCapture({ error("микрофон не нужен") }, { 1 }, {}, {})
        assertEquals(MicResult(MicEnd.NO_SPACE, 0), session.run(file))
    }

    @Test
    fun fileThatCannotBeCreatedClosesMicAndReportsFailure() {
        val mic = ScriptedMic(ArrayDeque(emptyList())) {}
        val session = MicCapture({ mic }, { Long.MAX_VALUE }, {}, {})
        assertEquals(MicResult(MicEnd.WRITE_FAILED, 0), session.run(folder.newFolder("not-a-file")))
        assertTrue(mic.closed)
    }

    @Test
    fun firstOpenFailureIsReported() {
        val file = folder.newFile("a.wav")
        val session = MicCapture({ throw MicUnavailable("нет") }, { Long.MAX_VALUE }, {}, {})
        val failed = runCatching { session.run(file) }.exceptionOrNull()
        assertTrue(failed is MicUnavailable)
    }

    @Test
    fun micIsClosedOnEveryExit() {
        val file = folder.newFile("a.wav")
        val mic = ScriptedMic(ArrayDeque(listOf(Step.Samples(1)))) { throw IOException("диск") }
        val session = MicCapture({ mic }, { Long.MAX_VALUE }, {}, {})
        val result = session.run(file)
        assertEquals(MicEnd.WRITE_FAILED, result.end)
        assertTrue(mic.closed)
    }

    @Test
    fun abandonedFileIsRepairedFromItsLength() {
        val file = folder.newFile("a.wav.part")
        val writer = MicWavWriter(file, RATE)
        writer.write(ShortArray(BLOCK) { 3 }, BLOCK)
        writer.sync()
        writer.close()
        file.appendBytes(byteArrayOf(9))

        assertEquals(BLOCK.toLong(), repairMicWav(file))
        val head = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(RATE, head.getInt(24))
        assertEquals(BLOCK * 2, head.getInt(40))
        assertEquals((WAV_HEADER_BYTES + BLOCK * 2).toLong(), file.length())
    }

    @Test
    fun fileWithoutAudioHasNothingToRepair() {
        val file = folder.newFile("a.wav.part")
        MicWavWriter(file, RATE).close()
        assertEquals(0L, repairMicWav(file))
    }

    @Test
    fun cancelAsksOnlyFromThreeSeconds() {
        assertFalse(MicRules.needsCancelConfirmation(2_999))
        assertTrue(MicRules.needsCancelConfirmation(3_000))
    }

    @Test
    fun fileNameCarriesDateAndIsSafeForResultPath() {
        val name = MicRules.fileName(1_794_057_303_000, ZoneOffset.ofHours(3))
        assertEquals("Запись 2026-11-07 16-15-03.wav", name)
        assertFalse(name.any { it in "/\\:*?\"<>|" })
    }

    @Test
    fun roomLeavesSpaceForPipelineCopies() {
        val mib = 1024L * 1024
        assertFalse(MicRules.outOfRoom(100 * mib, 264 * mib))
        assertTrue(MicRules.outOfRoom(100 * mib, 263 * mib))
        assertTrue(MicRules.outOfRoom(0, 63 * mib))
        assertTrue(MicRules.outOfRoom(4_000_000_000, Long.MAX_VALUE / 4))
    }

    @Test
    fun levelFollowsRealLoudness() {
        fun at(value: Short) = MicRules.level(ShortArray(100) { value }, 100)
        assertEquals(0f, at(0), 0f)
        assertEquals(1f, at(Short.MIN_VALUE), 0.001f)
        assertEquals(0.5f, at(1036), 0.01f)
        assertTrue(at(100) < at(1000))
        assertEquals(0f, MicRules.level(ShortArray(0), 0), 0f)
    }
}
