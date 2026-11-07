package ru.stenokot.core.merge

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.contract.DefaultTranscriptValidator
import ru.stenokot.core.contract.TranscriptEncoder
import ru.stenokot.core.contract.ValidationResult
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.AsrToken
import ru.stenokot.core.model.SpeakerTurn
import ru.stenokot.core.model.SpeechSegment
import ru.stenokot.core.model.Utterance

/** Границы реплик в настройке экспорта: именно они попадают в контрактный файл. */
class ExportIntervalsTest {
    private fun segment(index: Int, startMs: Long, endMs: Long, tokens: List<AsrToken>) =
        AsrSegment(SpeechSegment(index, startMs * 16, endMs * 16), tokens.joinToString("") { it.text }, tokens)

    private fun spoken(text: String, startMs: Long, stepMs: Long): List<AsrToken> =
        text.split(" ").mapIndexed { index, word -> AsrToken(" $word", startMs + index * stepMs) }

    private fun merge(durationMs: Long, tokens: List<AsrToken>, turns: List<SpeakerTurn>): List<Utterance> =
        DefaultMerger(durationMs).merge(listOf(segment(0, 0, durationMs, tokens)), turns)

    @Test
    fun startReachesTurnStart() {
        val out = merge(6_000, spoken("раз два три", 1_000, 400), listOf(SpeakerTurn(500, 5_000, 0)))
        assertEquals(500L, out.single().startMs)
        assertEquals(1_000L, out.single().words.first().startMs)
        assertEquals(5_000L, out.single().endMs)
    }

    @Test
    fun endCrossesUtteranceOfOtherSpeaker() {
        val tokens = spoken("раз два", 0, 400) + spoken("три четыре", 1_000, 400)
        val turns = listOf(SpeakerTurn(0, 3_000, 0), SpeakerTurn(950, 2_000, 1))
        val out = merge(4_000, tokens, turns)
        assertEquals(listOf("раз два", "три четыре"), out.map { it.text })
        assertEquals(3_000L, out[0].endMs)
        assertEquals(950L, out[1].startMs)
    }

    @Test
    fun utterancesOfOneSpeakerDoNotOverlap() {
        val tokens = spoken("раз два", 1_000, 400) + spoken("три четыре", 4_000, 400)
        val out = merge(6_000, tokens, listOf(SpeakerTurn(500, 5_500, 0)))
        assertEquals(2, out.size)
        assertEquals(4_000L, out[1].startMs)
        assertTrue(out[0].endMs <= out[1].startMs)
    }

    @Test
    fun startsStayOrderedWhenTurnBeginsEarlier() {
        val tokens = spoken("раз два", 1_000, 400) + spoken("три четыре", 2_000, 400)
        val turns = listOf(SpeakerTurn(800, 1_900, 0), SpeakerTurn(200, 3_000, 1))
        val out = merge(4_000, tokens, turns)
        assertEquals(out.map { it.startMs }.sorted(), out.map { it.startMs })
    }

    @Test
    fun silentPauseSplitsUtterance() {
        val tokens = spoken("раз два", 500, 300) + spoken("три четыре", 2_000, 300)
        val turns = listOf(SpeakerTurn(400, 1_200, 0), SpeakerTurn(1_900, 2_800, 0))
        val out = merge(3_000, tokens, turns)
        assertEquals(listOf("раз два", "три четыре"), out.map { it.text })
        assertEquals(setOf(0), out.map { it.speaker }.toSet())
    }

    @Test
    fun pauseInsideTurnDoesNotSplit() {
        val tokens = spoken("раз два", 500, 300) + spoken("три четыре", 2_000, 300)
        assertEquals(1, merge(3_000, tokens, listOf(SpeakerTurn(400, 2_800, 0))).size)
    }

    @Test
    fun pauseCoveredByOtherSpeakerDoesNotSplit() {
        val tokens = spoken("раз два", 500, 300) + spoken("три четыре", 2_000, 300)
        val turns = listOf(
            SpeakerTurn(400, 1_200, 0),
            SpeakerTurn(1_100, 2_000, 1),
            SpeakerTurn(1_900, 2_800, 0),
        )
        assertEquals(1, merge(3_000, tokens, turns).size)
    }

    @Test
    fun noDiarizationDoesNotSplitOnPause() {
        val tokens = spoken("раз два", 500, 300) + spoken("три четыре", 2_000, 300)
        assertEquals(1, merge(3_000, tokens, emptyList()).size)
    }

    @Test
    fun exportKeepsSmallSpeakerAfterBook() {
        val tokens = spoken("раз два три четыре пять шесть семь", 0, 400) + spoken("да нет", 3_000, 300)
        val turns = listOf(SpeakerTurn(0, 2_900, 0), SpeakerTurn(2_950, 3_700, 1), SpeakerTurn(3_800, 30_000, 0))
        assertEquals(setOf(0, 1), merge(31_000, tokens, turns).map { it.speaker }.toSet())
    }

    private fun randomAsr(random: Random, duration: Long): List<AsrSegment> {
        val vocabulary = listOf(" да", " нет", " слово", " при", "вет", ",", ".", "?", " —", " Москва", " 12", "%")
        val chunks = ArrayList<AsrSegment>()
        var at = 0L
        var index = 0
        while (at < duration - 500) {
            val end = minOf(duration, at + random.nextLong(500, 22_000))
            val tokens = ArrayList<AsrToken>()
            var t = at + random.nextLong(0, 5) * 40
            while (t < end) {
                tokens += AsrToken(vocabulary.random(random), t)
                t += 40L * random.nextInt(0, 30)
            }
            chunks += segment(index++, at, end, tokens)
            at = end + if (random.nextBoolean()) 0 else random.nextLong(0, 4_000)
        }
        return chunks
    }

    @Test
    fun invariantsHoldOnRandomInputProperty() {
        val random = Random(20261108)
        val validator = DefaultTranscriptValidator()
        val params = MergeParams.EXPORT
        repeat(1_500) { iteration ->
            val duration = random.nextLong(2_000, 90_000)
            val asr = randomAsr(random, duration)
            val turns = List(random.nextInt(0, 60)) {
                val start = random.nextLong(-200, duration)
                SpeakerTurn(start, start + random.nextLong(-50, 8_000), random.nextInt(-1, 8))
            }
            val out = DefaultMerger(duration, params).merge(asr, turns)
            val expected = asr.flatMap { it.tokens }.joinToString("") { it.text }.filter(Char::isLetterOrDigit)
            assertEquals("И1 $iteration", expected, out.joinToString("") { it.text }.filter(Char::isLetterOrDigit))
            out.forEach { u ->
                assertTrue("И2 $iteration", 0 <= u.startMs && u.startMs < u.endMs && u.endMs <= duration)
                assertTrue("начало $iteration", u.startMs <= u.words.first().startMs)
                assertTrue("конец $iteration", u.endMs >= u.words.last().startMs)
                assertTrue("длина $iteration", u.endMs - u.startMs <= params.maxUtteranceMs + params.maxVoteMs)
                assertEquals(u.words.joinToString(" ") { it.text }, u.text)
            }
            assertEquals("И3 $iteration", out.map { it.startMs }.sorted(), out.map { it.startMs })
            val starts = out.flatMap { it.words }.map { it.startMs }
            assertEquals("И3 $iteration", starts.sorted(), starts)
            assertEquals((1..out.size).toList(), out.map { it.id })
            assertEquals(ValidationResult.Ok, validator.validate(TranscriptEncoder.encode(out), duration))
            assertEquals("И6 $iteration", out, DefaultMerger(duration, params).merge(asr, turns.shuffled(random)))
        }
    }
}
