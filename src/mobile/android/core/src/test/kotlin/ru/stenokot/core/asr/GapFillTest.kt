package ru.stenokot.core.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.merge.DefaultMerger
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.AsrToken
import ru.stenokot.core.model.SpeakerTurn
import ru.stenokot.core.model.SpeechSegment

class GapFillTest {
    private val config = GapFillConfig()

    private fun tokens(vararg pairs: Pair<String, Long>) = pairs.map { AsrToken(it.first, it.second) }

    private fun segment(startMs: Long, endMs: Long, list: List<AsrToken>) =
        AsrSegment(SpeechSegment(0, startMs * 16, endMs * 16), list.joinToString("") { it.text }.trim(), list)

    private fun loud(probabilities: FloatArray) = GapAudio(probabilities, DoubleArray(probabilities.size) { 40.0 })

    @Test
    fun longGapBetweenWordsIsFound() {
        val chunk = segment(0, 3_000, tokens(" один" to 0, " два" to 400, " три" to 2_000, "." to 2_100))
        assertEquals(listOf(SpeechGap(440, 2_000, 2)), GapFill.findGaps(chunk, config))
    }

    @Test
    fun headAndTailGapsAreFound() {
        val chunk = segment(0, 6_000, tokens(" один" to 1_500, " два" to 3_000))
        assertEquals(
            listOf(SpeechGap(0, 1_500, 0), SpeechGap(1_540, 3_000, 1), SpeechGap(3_040, 6_000, 2)),
            GapFill.findGaps(chunk, config),
        )
    }

    @Test
    fun chunkWithoutWordsHasNoGaps() {
        assertEquals(emptyList<SpeechGap>(), GapFill.findGaps(segment(0, 20_000, tokens("." to 400)), config))
        assertEquals(emptyList<SpeechGap>(), GapFill.findGaps(segment(0, 20_000, emptyList()), config))
    }

    @Test
    fun openingMarkStaysWithRightWord() {
        val chunk = segment(0, 4_000, tokens(" да" to 0, "." to 100, " «" to 2_900, " Ну" to 3_000, "»" to 3_200))
        val gap = GapFill.findGaps(chunk, config).single()
        assertEquals(2, gap.insertAt)
        val filled = GapFill.insert(chunk, gap, segment(0, 4_000, tokens(" тихо" to 1_500, "." to 1_700)), config)
        assertEquals(listOf("да.", "тихо.", "«Ну»"), toWords(filled.tokens).map { it.text })
    }

    @Test
    fun mixedMarksBetweenWordsSkipTheGap() {
        val chunk = segment(0, 4_000, tokens(" да" to 0, " «" to 1_000, " —" to 2_000, " Ну" to 3_000))
        assertEquals(emptyList<SpeechGap>(), GapFill.findGaps(chunk, config))
    }

    @Test
    fun speechSpanNeedsEnoughConfidentSpeech() {
        val gap = SpeechGap(1_000, 3_000, 1)
        val short = FloatArray(60).also { it.fill(0.9f, 10, 29) }
        assertNull(GapFill.speechSpan(gap, loud(short), 0.0, config))
        val unsure = FloatArray(60).also { it.fill(0.7f, 10, 40) }
        assertNull(GapFill.speechSpan(gap, loud(unsure), 0.0, config))
        val long = FloatArray(60).also { it.fill(0.9f, 10, 30) }
        assertEquals(1_120L..2_160L, GapFill.speechSpan(gap, loud(long), 0.0, config))
    }

    @Test
    fun spanCoversUnsureSpeechAroundConfidentSpeech() {
        val gap = SpeechGap(1_000, 3_000, 1)
        val track = FloatArray(60).also {
            it.fill(0.6f, 5, 10)
            it.fill(0.9f, 10, 30)
            it.fill(0.6f, 30, 40)
        }
        assertEquals(1_000L..2_480L, GapFill.speechSpan(gap, loud(track), 0.0, config))
    }

    @Test
    fun speechBarelyAboveBackgroundIsSkipped() {
        val gap = SpeechGap(1_000, 3_000, 1)
        val track = FloatArray(60) { 0.95f }
        assertNull(GapFill.speechSpan(gap, GapAudio(track, DoubleArray(60) { -30.0 }), -44.0, config))
        assertEquals(1_000L..3_000L, GapFill.speechSpan(gap, GapAudio(track, DoubleArray(60) { -29.0 }), -44.0, config))
    }

    @Test
    fun speechSpanStaysInsideTheGap() {
        val gap = SpeechGap(1_000, 2_000, 1)
        val all = FloatArray(32) { 0.9f }
        assertEquals(1_000L..2_000L, GapFill.speechSpan(gap, loud(all), 0.0, config))
    }

    @Test
    fun floorIsTenthPercentileOfFullWindows() {
        val samples = FloatArray(3 * 512 + 100)
        samples.fill(0.001f, 0, 512)
        samples.fill(0.01f, 512, 1024)
        samples.fill(0.1f, 1024, 1536)
        samples.fill(0.9f, 1536, samples.size)
        val energies = GapFill.windowEnergiesDb(samples)
        assertEquals(3, energies.size)
        assertEquals(-60.0, energies[0], 1e-3)
        assertEquals(-20.0, energies[2], 1e-3)
        assertEquals(-56.0, GapFill.floorDb(samples), 1e-3)
        assertEquals(-100.0, GapFill.floorDb(FloatArray(512)), 1e-9)
    }

    @Test
    fun insertKeepsOnlyWordsInsideGuardsAndOldTokensIntact() {
        val old = tokens(" пофиг" to 500, "." to 600, " Ну" to 4_000, " всё" to 4_300)
        val chunk = segment(0, 5_000, old)
        val gap = GapFill.findGaps(chunk, config).single()
        assertEquals(SpeechGap(540, 4_000, 2), gap)
        val decoded = segment(
            540,
            4_000,
            tokens(" фиг" to 600, " это" to 1_600, " я" to 1_800, "," to 1_900, " ну" to 3_900),
        )
        val filled = GapFill.insert(chunk, gap, decoded, config)
        assertEquals(old.subList(0, 2), filled.tokens.subList(0, 2))
        assertEquals(old.subList(2, 4), filled.tokens.subList(5, 7))
        assertEquals(listOf("пофиг.", "это", "я,", "Ну", "всё"), toWords(filled.tokens).map { it.text })
        assertEquals("пофиг. это я, Ну всё", filled.text)
    }

    @Test
    fun nothingAcceptedLeavesSegmentUntouched() {
        val chunk = segment(0, 5_000, tokens(" пофиг" to 500, " Ну" to 4_000))
        val gap = GapFill.findGaps(chunk, config).single()
        val decoded = segment(540, 4_000, tokens(" фиг" to 600, " ну" to 3_900))
        assertSame(chunk, GapFill.insert(chunk, gap, decoded, config))
    }

    @Test
    fun onlyGapsWithSpeechAreDecoded() {
        val chunk = segment(0, 9_000, tokens(" один" to 0, " два" to 3_000, " три" to 6_000))
        val asked = ArrayList<SpeechSegment>()
        val filled = GapFill.fill(
            segment = chunk,
            config = config,
            floorDb = 0.0,
            listen = { startMs, endMs ->
                loud(FloatArray(((endMs - startMs + 31) / 32).toInt()) { if (startMs == 3_040L) 0.9f else 0.1f })
            },
            decode = { range ->
                asked += range
                segment(range.startSample / 16, range.endSample / 16, tokens(" тихо" to 4_000))
            },
        )
        assertEquals(listOf(SpeechSegment(0, 3_040L * 16, 6_000L * 16)), asked)
        assertEquals(listOf("один", "два", "тихо", "три"), toWords(filled.tokens).map { it.text })
    }

    /**
     * Запись владельца: реплика второго голоса 7152-9008 мс тише первого на 13-17 дБ и в целом срезе
     * пропадает. Токены среза взяты из журнала телефона, вероятности, энергия окон над фоном и ответ
     * модели на паузу из прогона той же модели на компьютере.
     */
    @Test
    fun rec1QuietVoiceLandsInsideItsTurn() {
        val texts = listOf(
            " Алло", ",", " все", "м", " при", "вет", "!", " Ну", ",", " с", "ка", "жи", " что", "-", "нибудь", ".",
            " Ну", " всё", ",", " по", "фи", "г", ".", " Д", "ва", " ", "сти", "ке", "ра", ".", " С", "ка", "жи", " чё",
            "-", "нибудь", " ещё", ".", " К", "и", "ри", "л", "л", ".", " А", "га", ".", " Я", " тебя", " понял", ".",
        )
        val starts = listOf(
            1200L, 1640, 1920, 2160, 2480, 2680, 3240, 5080, 5320, 5480, 5560, 5640, 5840, 6000, 6000, 6400, 9800,
            10080, 10240, 10320, 10480, 10600, 10840, 11240, 11360, 11520, 11600, 11760, 11960, 12240, 12760, 12840,
            12920, 13080, 13240, 13240, 13520, 13880, 14360, 14400, 14480, 14600, 14680, 15920, 17000, 17200, 17600,
            18320, 18520, 18720, 18960,
        )
        val chunk = segment(0, 20_136, texts.indices.map { AsrToken(texts[it], starts[it]) })
        val gapTrack = intArrayOf(
            5, 6, 5, 3, 2, 3, 1, 0, 0, 0, 1, 1, 2, 1, 1, 6, 9, 7, 5, 3, 4, 6, 4, 2, 2, 1, 1, 1, 1, 0, 1, 11, 39, 42,
            77, 65, 57, 70, 59, 48, 55, 80, 96, 95, 95, 94, 95, 93, 92, 91, 79, 96, 96, 91, 53, 36, 94, 94, 88, 54,
            69, 86, 87, 95, 98, 97, 97, 96, 95, 90, 72, 54, 54, 95, 95, 92, 95, 95, 90, 96, 97, 95, 96, 88, 67, 37,
            6, 2, 8, 94, 96, 95, 94, 82, 75, 69, 64, 53, 39, 29, 24, 17, 11, 10, 7, 6, 6, 5, 5, 4, 4, 4, 3, 3, 3, 3,
            2, 1,
        )
        val gapEnergy = intArrayOf(
            68, 70, 72, 68, 57, 42, 27, 17, 13, 8, 4, 9, 6, 4, -1, -1, 2, 5, 3, 3, 1, 17, 15, 9, 4, 4, -1, 1, -1, 1,
            -1, -1, 16, 24, 54, 57, 46, 61, 65, 58, 52, 60, 68, 69, 69, 65, 68, 70, 63, 56, 43, 58, 62, 41, 34, 24,
            60, 63, 40, 36, 37, 52, 55, 58, 67, 63, 59, 65, 57, 47, 43, 38, 32, 55, 60, 56, 49, 51, 38, 51, 55, 49,
            52, 48, 40, 43, 28, 22, 35, 52, 56, 54, 39, 29, 21, 24, 28, 23, 20, 20, 30, 23, 20, 18, 16, 18, 19, 18,
            16, 17, 18, 14, 14, 13, 14, 14, 14,
        )
        val quiet = tokens(
            " Это" to 7208, " я" to 7408, " так" to 7568, " не" to 7728, " хотел" to 7888, "," to 8168,
            " хотел" to 8288, " сам" to 8528, " по" to 8768, "те" to 8888, "сти" to 9088, "ть" to 9248, "." to 9368,
        )
        val asked = ArrayList<SpeechSegment>()
        val filled = GapFill.fill(
            segment = chunk,
            config = config,
            floorDb = 0.0,
            listen = { startMs, endMs ->
                val count = ((endMs - startMs) * 16 + 511).toInt() / 512
                val target = startMs == 6_040L
                GapAudio(
                    FloatArray(count) { if (target && it < gapTrack.size) gapTrack[it] / 100f else 0f },
                    DoubleArray(count - 1) { if (target && it < gapEnergy.size) gapEnergy[it].toDouble() else 0.0 },
                )
            },
            decode = { range ->
                asked += range
                val text = if (range == SpeechSegment(0, 6_928L * 16, 9_376L * 16)) quiet else emptyList()
                segment(range.startSample / 16, range.endSample / 16, text)
            },
        )

        assertEquals(listOf(SpeechSegment(0, 6_928L * 16, 9_376L * 16)), asked)
        assertEquals(chunk.tokens, filled.tokens.filterNot { it in quiet })
        val added = toWords(filled.tokens).filter { it.startMs in 6_040L until 9_800L }
        assertEquals(
            listOf("Это", "я", "так", "не", "хотел,", "хотел", "сам", "потестить."),
            added.map { it.text },
        )
        assertTrue(added.all { it.startMs in 7_152L..9_008L })

        val turns = listOf(
            SpeakerTurn(1330, 3237, 0, 0.596f), SpeakerTurn(5178, 6207, 0, 0.632f), SpeakerTurn(7152, 9008, 1, 0.789f),
            SpeakerTurn(9869, 13750, 0, 0.655f), SpeakerTurn(14375, 14965, 1, 0.813f), SpeakerTurn(17007, 19910, 0, 0.682f),
        )
        val before = DefaultMerger(20_136).merge(listOf(chunk), turns)
        val after = DefaultMerger(20_136).merge(listOf(filled), turns)
        assertEquals(1, before.map { it.speaker }.toSet().size)
        assertEquals(2, after.map { it.speaker }.toSet().size)
        val second = after.filter { it.speaker != before.first().speaker }
        assertEquals("Это я так не хотел, хотел сам потестить.", second.joinToString(" ") { it.text })
    }
}
