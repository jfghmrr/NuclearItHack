package ru.stenokot.app.ui.result

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Огибающая должна стоить одинаково на любой длине и не зависеть от единичного всплеска. */
class WaveformTest {
    private fun level(value: Byte): Int = value.toInt() and 0xFF

    @Test
    fun longRecordingReadsOnlySmallProbes() {
        val samples = 16_000L * 3 * 3_600
        var read = 0L
        val envelope = computeEnvelope(samples) { _, count ->
            read += count
            FloatArray(count) { 0.1f }
        }
        assertEquals(720, envelope.size)
        assertEquals(720L * 4_096, read)
    }

    @Test
    fun recordingShorterThanBucketsGivesOneLevelPerSample() {
        assertEquals(3, computeEnvelope(3) { _, count -> FloatArray(count) { 0.5f } }.size)
    }

    @Test
    fun emptyRecordingHasNoEnvelope() {
        assertEquals(0, computeEnvelope(0) { _, _ -> error("читать нечего") }.size)
    }

    @Test
    fun silenceStaysFlatAtZero() {
        val envelope = computeEnvelope(10_000) { _, count -> FloatArray(count) }
        assertTrue(envelope.all { it == 0.toByte() })
    }

    @Test
    fun singleLoudBurstDoesNotFlattenSpeech() {
        val total = 7_200_000L
        val envelope = computeEnvelope(total) { start, count ->
            FloatArray(count) { if (start < total / 720) 1f else 0.1f }
        }
        assertEquals(255, level(envelope[0]))
        assertEquals(255, level(envelope[400]))
    }

    @Test
    fun quietPartStaysLowerThanLoudPart() {
        val total = 7_200_000L
        val envelope = computeEnvelope(total) { start, count ->
            FloatArray(count) { if (start < total / 2) 0.2f else 0.04f }
        }
        assertEquals(255, level(envelope[10]))
        assertEquals(51f, level(envelope[700]).toFloat(), 1f)
    }

    @Test
    fun barsAverageTheirBuckets() {
        val envelope = byteArrayOf(0, 255.toByte(), 255.toByte(), 255.toByte())
        assertArrayEquals(floatArrayOf(0.5f, 1f), envelopeBars(envelope, 2), 0.01f)
    }

    @Test
    fun moreBarsThanBucketsRepeatLevels() {
        assertEquals(8, envelopeBars(byteArrayOf(10, 20), 8).size)
    }

    @Test
    fun noBarsForEmptyEnvelope() {
        assertEquals(0, envelopeBars(ByteArray(0), 10).size)
        assertEquals(0, envelopeBars(byteArrayOf(1), 0).size)
    }
}
