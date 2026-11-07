package ru.stenokot.core.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.model.Stage

class MetricsTest {
    private val stages = mapOf(
        Stage.IMPORT to 1_000L,
        Stage.DECODE to 2_000L,
        Stage.VAD to 2_000L,
        Stage.LANG_ID to 1_000L,
        Stage.ASR to 10_000L,
        Stage.DIARIZE to 20_000L,
        Stage.MERGE to 500L,
        Stage.VALIDATE to 500L,
    )

    @Test
    fun coreExcludesLlmAndFullIncludesIt() {
        val values = RtfCalculator.compute(100_000, stages + (Stage.LLM to 10_000L), overheadMs = 3_000)
        assertEquals(0.40, values.core!!, 1e-9)
        assertEquals(0.50, values.full!!, 1e-9)
    }

    @Test
    fun fullIsUnknownWithoutLlmRecord() {
        val values = RtfCalculator.compute(100_000, stages)
        assertEquals(0.37, values.core!!, 1e-9)
        assertNull(values.full)
    }

    @Test
    fun skippedLlmEqualsCore() {
        val values = RtfCalculator.compute(100_000, stages + (Stage.LLM to 0L))
        assertEquals(values.core, values.full)
    }

    @Test
    fun zeroDurationGivesNoValues() {
        assertEquals(RtfValues(null, null), RtfCalculator.compute(0, stages))
        assertNull(RtfCalculator.stage(10, 0))
        assertEquals(0.5, RtfCalculator.stage(50, 100)!!, 1e-9)
    }

    private val speed = DeviceSpeed.fromRun(stages, 100_000)!!

    @Test
    fun speedNeedsLongEnoughRunWithAsr() {
        assertNull(DeviceSpeed.fromRun(stages, 5_000))
        assertNull(DeviceSpeed.fromRun(mapOf(Stage.DECODE to 1L), 100_000))
        assertEquals(0.1, speed.stageRtf.getValue(Stage.ASR), 1e-9)
        assertTrue(Stage.LLM !in DeviceSpeed.fromRun(stages + (Stage.LLM to 5L), 100_000)!!.stageRtf)
    }

    @Test
    fun preflightWithoutDurationIsHidden() {
        assertNull(EtaEstimator().preflight(null))
        assertNull(EtaEstimator(speed).preflight(0))
    }

    @Test
    fun preflightWithoutMeasureIsLabelledRough() {
        val range = EtaEstimator().preflight(42 * 60_000L)!!
        assertEquals(EtaBasis.ROUGH, range.basis)
        assertTrue(range.minMs < range.maxMs)
        assertTrue(range.maxMs > 2 * range.minMs - 10_000)
    }

    @Test
    fun preflightUsesMeasuredSpeed() {
        val range = EtaEstimator(speed).preflight(1_000_000)!!
        assertEquals(EtaBasis.MEASURED, range.basis)
        assertEquals((0.35 * 1_000_000 * 0.85).toLong(), range.minMs)
        assertEquals((0.35 * 1_000_000 * 1.25).toLong(), range.maxMs)
    }

    @Test
    fun remainingIsHiddenWithoutEvidence() {
        val estimator = EtaEstimator()
        assertNull(estimator.remaining(EtaProgress(600_000, Stage.DECODE, 0.5f, 5_000)))
        assertNull(estimator.remaining(EtaProgress(600_000, Stage.ASR, 0.01f, 5_000)))
        assertNull(estimator.remaining(EtaProgress(600_000, Stage.ASR, 0.5f, 1_000)))
        assertNull(estimator.remaining(EtaProgress(0, Stage.ASR, 0.5f, 5_000)))
    }

    @Test
    fun remainingUsesRealProgressOfCurrentStage() {
        val range = EtaEstimator().remaining(EtaProgress(600_000, Stage.ASR, 0.5f, 60_000))
        assertNotNull(range)
        assertEquals(EtaBasis.ROUGH, range!!.basis)
        val asrLeft = 60_000.0
        val asrRate = 120_000.0 / 600_000
        val diarize = 0.30 * (asrRate / 0.12) * 600_000
        assertEquals((asrLeft + diarize) * 0.7, range.minMs.toDouble(), 1.0)
    }

    @Test
    fun remainingUsesDeviceSpeed() {
        val range = EtaEstimator(speed).remaining(EtaProgress(1_000_000, Stage.DIARIZE, 0.5f, 100_000))!!
        assertEquals(EtaBasis.MEASURED, range.basis)
        assertEquals((100_000 * 0.85).toLong(), range.minMs)
        assertEquals((100_000 * 1.25).toLong(), range.maxMs)
    }

    @Test
    fun remainingWithoutFractionUsesSpeed() {
        val range = EtaEstimator(speed).remaining(EtaProgress(1_000_000, Stage.DIARIZE))!!
        assertEquals((200_000 * 0.85).toLong(), range.minMs)
    }

    @Test
    fun finishedAsrScalesDiarizationWithoutFraction() {
        val range = EtaEstimator().remaining(
            EtaProgress(600_000, Stage.DIARIZE, finishedStageMs = mapOf(Stage.ASR to 72_000L)),
        )!!
        assertEquals(EtaBasis.ROUGH, range.basis)
        assertEquals(0.30 * 600_000 * 0.7, range.minMs.toDouble(), 1.0)
    }

    @Test
    fun remainingShrinksAsWorkProgresses() {
        val estimator = EtaEstimator(speed)
        val early = estimator.remaining(EtaProgress(600_000, Stage.ASR, 0.2f, 10_000))!!
        val late = estimator.remaining(EtaProgress(600_000, Stage.ASR, 0.9f, 50_000))!!
        assertTrue(late.maxMs < early.maxMs)
    }

    @Test
    fun lightStagesAtEndGiveNearZero() {
        assertEquals(0L, EtaEstimator().remaining(EtaProgress(600_000, Stage.VALIDATE))!!.maxMs)
    }

    @Test
    fun llmStageFollowsObservedWindows() {
        val range = EtaEstimator().remaining(EtaProgress(600_000, Stage.LLM, 0.25f, 20_000))!!
        assertEquals(51_000L, range.minMs)
        assertEquals(75_000L, range.maxMs)
        assertNull(EtaEstimator().remaining(EtaProgress(600_000, Stage.LLM)))
        assertNull(EtaEstimator().remaining(EtaProgress(600_000, Stage.LLM, 0f, 20_000)))
    }
}
