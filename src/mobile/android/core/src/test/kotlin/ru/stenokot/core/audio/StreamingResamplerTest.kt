package ru.stenokot.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingResamplerTest {
    private fun collect(rate: Int, channels: Int, input: FloatArray, block: Int = 1_024): FloatArray {
        val resampler = StreamingResampler(rate, channels)
        val out = ArrayList<FloatArray>()
        val sink = SampleSink { samples, count -> out += samples.copyOf(count) }
        var offset = 0
        while (offset < input.size) {
            val length = minOf(block * channels, input.size - offset)
            resampler.process(input, offset, length, sink)
            offset += length
        }
        resampler.flush(sink)
        val total = out.sumOf { it.size }
        val merged = FloatArray(total)
        var at = 0
        for (part in out) {
            part.copyInto(merged, at)
            at += part.size
        }
        return merged
    }

    private fun tone(rate: Int, hz: Double, seconds: Double, gain: Double = 0.5): FloatArray =
        FloatArray((rate * seconds).toInt()) { (gain * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += x[i].toDouble() * x[i]
        return sqrt(sum / (to - from))
    }

    /** Коэффициент передачи в децибелах по стационарной средней части. */
    private fun gainDb(rate: Int, hz: Double): Double {
        val input = tone(rate, hz, 2.0)
        val output = collect(rate, 1, input)
        val level = rms(output, 4_000, output.size - 4_000)
        return 20 * log10(max(level, 1e-9) / (0.5 / sqrt(2.0)))
    }

    @Test
    fun outputLengthMatchesTimeScaleExactly() {
        for (rate in listOf(8_000, 11_025, 16_000, 22_050, 44_100, 48_000, 96_000, 12_345)) {
            for (frames in listOf(1, 7, 1_000, rate, 10 * rate + 3)) {
                val out = collect(rate, 1, FloatArray(frames))
                val expected = (frames * 16_000L + rate - 1) / rate
                assertEquals("$rate $frames", expected, out.size.toLong())
            }
        }
    }

    /** Импульсы в точные моменты времени должны выйти в те же моменты шкалы 16 кГц. */
    @Test
    fun timeScaleIsPreservedAtAllRates() {
        for (rate in listOf(8_000, 22_050, 44_100, 48_000)) {
            val seconds = 120
            val input = FloatArray(seconds * rate)
            val marks = listOf(1.0, 17.25, 59.5, 118.75)
            val width = rate / 400
            for (mark in marks) {
                val center = (mark * rate).toInt()
                for (i in -width..width) {
                    input[center + i] = (1.0 - abs(i).toDouble() / width).toFloat() * 0.8f
                }
            }
            val out = collect(rate, 1, input)
            assertEquals(seconds * 16_000, out.size)
            for (mark in marks) {
                val expected = (mark * 16_000).toInt()
                val from = expected - 200
                val found = (from until expected + 200).maxBy { out[it] }
                assertTrue("$rate $mark $found $expected", abs(found - expected) <= 1)
            }
        }
    }

    @Test
    fun longRunDoesNotDrift() {
        val rate = 44_100
        val output = StreamingResampler(rate, 1)
        var count = 0L
        val sink = SampleSink { _, n -> count += n }
        val block = FloatArray(44_100)
        repeat(3_600) { output.process(block, 0, block.size, sink) }
        output.flush(sink)
        assertEquals(3_600L * 16_000, count)
    }

    @Test
    fun blockSizeDoesNotChangeResult() {
        val input = tone(44_100, 440.0, 1.0)
        val a = collect(44_100, 1, input, block = 1)
        val b = collect(44_100, 1, input, block = 777)
        val c = collect(44_100, 1, input, block = 100_000)
        assertEquals(a.size, b.size)
        assertEquals(a.size, c.size)
        for (i in a.indices) {
            assertEquals(a[i], b[i], 1e-6f)
            assertEquals(a[i], c[i], 1e-6f)
        }
    }

    @Test
    fun passthroughCopiesSamples() {
        val input = tone(16_000, 300.0, 0.5)
        val out = collect(16_000, 1, input)
        assertTrue(out.contentEquals(input))
    }

    @Test
    fun passbandIsFlatForSpeech() {
        for (rate in listOf(22_050, 44_100, 48_000)) {
            for (hz in listOf(100.0, 1_000.0, 3_000.0, 5_000.0, 6_000.0)) {
                val db = gainDb(rate, hz)
                assertTrue("$rate $hz $db", abs(db) < 0.5)
            }
        }
    }

    @Test
    fun upsamplingKeepsTones() {
        assertTrue(abs(gainDb(8_000, 1_000.0)) < 0.5)
        assertTrue(abs(gainDb(8_000, 3_000.0)) < 0.5)
        assertTrue(abs(gainDb(11_025, 4_000.0)) < 0.5)
    }

    @Test
    fun aliasesAreRejected() {
        for (rate in listOf(44_100, 48_000)) {
            for (hz in listOf(9_000.0, 12_000.0, 15_000.0, 20_000.0)) {
                val db = gainDb(rate, hz)
                assertTrue("$rate $hz $db", db < -60)
            }
        }
        assertTrue(gainDb(22_050, 10_000.0) < -60)
    }

    @Test
    fun constantSignalKeepsLevel() {
        val out = collect(44_100, 1, FloatArray(44_100) { 0.25f })
        for (i in 1_000 until out.size - 1_000) assertEquals(0.25f, out[i], 1e-4f)
    }

    @Test
    fun stereoIsAveraged() {
        val stereo = FloatArray(2 * 16_000)
        for (i in 0 until 16_000) {
            stereo[2 * i] = 0.5f
            stereo[2 * i + 1] = -0.25f
        }
        val out = collect(16_000, 2, stereo)
        assertEquals(16_000, out.size)
        assertEquals(0.125f, out[100], 1e-6f)
    }

    @Test
    fun sixChannelsAtSourceRateAreAveragedAndResampled() {
        val input = FloatArray(6 * 48_000) { 0.3f }
        val out = collect(48_000, 6, input)
        assertEquals(16_000, out.size)
        assertEquals(0.3f, out[8_000], 1e-4f)
    }

    @Test
    fun shortInputMatchesFloatInput() {
        val shorts = ShortArray(44_100) { (10_000 * sin(2 * PI * 300 * it / 44_100)).toInt().toShort() }
        val floats = FloatArray(shorts.size) { shorts[it] / 32_768f }
        val viaFloat = collect(44_100, 1, floats)
        val resampler = StreamingResampler(44_100, 1)
        val parts = ArrayList<Float>()
        val sink = SampleSink { samples, count -> repeat(count) { parts += samples[it] } }
        resampler.process(shorts, 0, shorts.size, sink)
        resampler.flush(sink)
        assertEquals(viaFloat.size, parts.size)
        for (i in viaFloat.indices) assertEquals(viaFloat[i], parts[i], 1e-5f)
    }

    @Test
    fun outputStaysInRange() {
        var low = 0f
        var high = 0f
        val resampler = StreamingResampler(48_000, 1)
        val square = FloatArray(48_000) { if ((it / 40) % 2 == 0) 1f else -1f }
        val sink = SampleSink { samples, count ->
            for (i in 0 until count) {
                low = minOf(low, samples[i])
                high = maxOf(high, samples[i])
            }
        }
        resampler.process(square, 0, square.size, sink)
        resampler.flush(sink)
        assertTrue(low >= -1f && high <= 1f)
    }

    @Test
    fun speedIsFarAboveRealTime() {
        val input = FloatArray(44_100 * 60) { (0.5 * sin(it * 0.01)).toFloat() }
        repeat(3) { collect(44_100, 1, input, block = 4_096) }
        val started = System.nanoTime()
        collect(44_100, 1, input, block = 4_096)
        val seconds = (System.nanoTime() - started) / 1e9
        val speed = 60.0 / seconds
        println("скорость передискретизации 44.1 кГц: ${speed.toInt()}x реального времени")
        assertTrue("$speed", speed > 100)
    }
}
