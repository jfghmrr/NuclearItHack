package ru.stenokot.core.vad

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.model.SpeechSegment

class ChunkPlannerTest {
    private val planner = ChunkPlanner()
    private val windowMs = 32.0

    /** Строит вероятности по участкам: миллисекунды начала и конца, значение на участке. */
    private fun probabilities(totalMs: Long, base: Float, vararg parts: Triple<Long, Long, Float>): FloatArray {
        val out = FloatArray((totalMs / windowMs).toInt()) { base }
        for ((from, to, value) in parts) {
            for (i in (from / windowMs).toInt() until (to / windowMs).toInt().coerceAtMost(out.size)) out[i] = value
        }
        return out
    }

    private fun total(ms: Long) = ms * 16

    private fun SpeechSegment.startMs() = startSample / 16

    private fun SpeechSegment.endMs() = endSample / 16

    private fun SpeechSegment.lengthMs() = endMs() - startMs()

    @Test
    fun silenceGivesNoChunks() {
        assertTrue(planner.plan(FloatArray(1000) { 0.01f }, total(32_000)).isEmpty())
    }

    @Test
    fun shortSilentFileGivesNoChunks() {
        assertTrue(planner.plan(FloatArray(20) { 0.01f }, total(640)).isEmpty())
    }

    @Test
    fun shortSpeechIsOneChunk() {
        val out = planner.plan(FloatArray(250) { 0.9f }, total(8_000))
        assertEquals(1, out.size)
        assertEquals(0L, out[0].startSample)
        assertEquals(total(8_000), out[0].endSample)
    }

    @Test
    fun longPauseIsSkippedWithPadding() {
        val probs = probabilities(
            30_000, 0.9f,
            Triple(5_000L, 20_000L, 0.05f),
        )
        val out = planner.plan(probs, total(30_000))
        assertEquals(2, out.size)
        assertTrue(out[0].endMs() in 5_100L..5_300L)
        assertTrue(out[1].startMs() in 19_700L..19_900L)
        assertEquals(total(30_000), out[1].endSample)
    }

    @Test
    fun shortPauseStaysInChunk() {
        val probs = probabilities(10_000, 0.9f, Triple(3_000L, 4_900L, 0.05f))
        assertEquals(1, planner.plan(probs, total(10_000)).size)
    }

    @Test
    fun pauseAboveSkipProbabilityIsNotSkipped() {
        val probs = probabilities(10_000, 0.9f, Triple(2_000L, 7_000L, 0.25f))
        assertEquals(1, planner.plan(probs, total(10_000)).size)
    }

    @Test
    fun blipInsidePauseDoesNotBreakIt() {
        val probs = probabilities(
            20_000, 0.05f,
            Triple(0L, 3_000L, 0.9f),
            Triple(7_000L, 7_064L, 0.5f),
            Triple(15_000L, 20_000L, 0.9f),
        )
        assertEquals(2, planner.plan(probs, total(20_000)).size)
    }

    @Test
    fun shortAnswerIsKept() {
        val probs = probabilities(20_000, 0.05f, Triple(10_000L, 10_130L, 0.9f))
        val out = planner.plan(probs, total(20_000))
        assertEquals(1, out.size)
        assertTrue(out[0].startMs() <= 10_000 && out[0].endMs() >= 10_130)
    }

    @Test
    fun continuousSpeechCutsAtDeepestDipInTargetRange() {
        val probs = probabilities(30_000, 0.9f, Triple(17_000L, 17_200L, 0.05f))
        val out = planner.plan(probs, total(30_000))
        assertEquals(2, out.size)
        assertTrue(out[0].endMs() in 17_000L..17_200L)
        assertEquals(out[0].endSample, out[1].startSample)
    }

    @Test
    fun dipOutsideTargetRangeIsIgnored() {
        val probs = probabilities(30_000, 0.9f, Triple(5_000L, 5_300L, 0.05f))
        val out = planner.plan(probs, total(30_000))
        assertEquals(2, out.size)
        assertEquals(20_000L, out[0].endMs())
        assertEquals(18_000L, out[1].startMs())
    }

    @Test
    fun noDipGivesOverlap() {
        val out = planner.plan(FloatArray(1875) { 0.95f }, total(60_000))
        out.forEach { assertTrue(it.lengthMs() <= 22_000) }
        out.zipWithNext().forEach { (a, b) -> assertEquals(2_000L, a.endMs() - b.startMs()) }
        assertEquals(0L, out.first().startSample)
        assertEquals(total(60_000), out.last().endSample)
    }

    @Test
    fun spansInputSkipsLongGapsOnly() {
        val out = planner.planFromSpans(
            listOf(
                SpeechSegment(0, total(1_000), total(3_000)),
                SpeechSegment(1, total(4_000), total(6_000)),
                SpeechSegment(2, total(12_000), total(13_000)),
            ),
            total(20_000),
        )
        assertEquals(2, out.size)
        assertEquals(0L, out[0].startMs())
        assertTrue(out[0].endMs() in 6_000L..6_300L)
        assertTrue(out[1].startMs() in 11_700L..12_000L)
    }

    @Test
    fun randomProbabilitiesRespectLimitsProperty() {
        val random = Random(3)
        repeat(300) {
            val windows = random.nextInt(50, 20_000)
            val probs = FloatArray(windows)
            var i = 0
            while (i < windows) {
                val run = random.nextInt(1, 800)
                val value = random.nextFloat()
                probs.fill(value, i, minOf(windows, i + run))
                i += run
            }
            val totalSamples = windows * 512L + random.nextInt(0, 512)
            val out = planner.plan(probs, totalSamples)
            out.forEachIndexed { index, chunk ->
                assertEquals(index, chunk.index)
                assertTrue(chunk.startSample >= 0 && chunk.startSample < chunk.endSample)
                assertTrue(chunk.endSample <= totalSamples)
                assertTrue(chunk.lengthMs() <= 22_000)
            }
            out.zipWithNext().forEach { (a, b) ->
                assertTrue(a.startSample < b.startSample && a.endSample < b.endSample)
                assertTrue(a.endMs() - b.startMs() <= 2_000)
            }
        }
    }

    @Test
    fun speechIsNeverDroppedProperty() {
        val random = Random(9)
        repeat(200) {
            val windows = random.nextInt(100, 10_000)
            val probs = FloatArray(windows) { 0.01f }
            repeat(random.nextInt(1, 30)) {
                val from = random.nextInt(windows)
                probs.fill(0.9f, from, minOf(windows, from + random.nextInt(4, 600)))
            }
            val out = planner.plan(probs, windows * 512L)
            for (w in 0 until windows) {
                if (probs[w] < 0.5f) continue
                val at = w * 512L + 256
                assertTrue(out.any { at >= it.startSample && at < it.endSample })
            }
        }
    }
}
