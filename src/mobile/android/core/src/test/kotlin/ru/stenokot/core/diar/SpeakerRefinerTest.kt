package ru.stenokot.core.diar

import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.asr.attachPunctuation
import ru.stenokot.core.asr.stitchChunks
import ru.stenokot.core.contract.DefaultTranscriptValidator
import ru.stenokot.core.contract.TranscriptEncoder
import ru.stenokot.core.contract.ValidationResult
import ru.stenokot.core.merge.DefaultMerger
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.AsrToken
import ru.stenokot.core.model.SpeakerTurn
import ru.stenokot.core.model.SpeechSegment
import ru.stenokot.core.model.Word
import ru.stenokot.core.storage.DiarJournalLine
import ru.stenokot.core.storage.DiarTurnRecord

/** Сборка говорящих по фразам: кто с кем объединился и что слова остались прежними. */
class SpeakerRefinerTest {
    private val refiner = SpeakerRefiner()

    private fun voice(index: Int, noise: Int = 0): FloatArray {
        val values = FloatArray(16)
        values[index] = 1f
        values[15 - (noise % 8)] += 0.2f
        val length = sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(16) { values[it] / length }
    }

    private fun phrase(startMs: Long, endMs: Long, speaker: Int) =
        Phrase(startMs, endMs, speaker, listOf(TimeSpan(startMs, endMs)))

    private fun spans(vararg ms: Long) = ms.toList().chunked(2).map { TimeSpan(it[0], it[1]) }

    @Test
    fun mixedClusterIsSplitByVoice() {
        val phrases = List(8) { phrase(it * 5_000L, it * 5_000L + 4_000, 0) }
        val prints = Array<FloatArray?>(8) { voice(it % 2, it) }
        val decision = refiner.decide(phrases, prints)!!
        assertEquals(2, decision.labels.toSet().size)
        assertTrue(decision.labels.indices.all { decision.labels[it] == decision.labels[it % 2] })
    }

    @Test
    fun oneVoiceSplitByDiarizerIsJoined() {
        val phrases = List(6) { phrase(it * 5_000L, it * 5_000L + 4_000, it % 3) }
        val prints = Array<FloatArray?>(6) { voice(0, it) }
        assertEquals(1, refiner.decide(phrases, prints)!!.labels.toSet().size)
    }

    @Test
    fun tooFewLongPhrasesLeaveTurnsAlone() {
        val phrases = listOf(phrase(0, 4_000, 0), phrase(5_000, 5_800, 1))
        val turns = listOf(SpeakerTurn(0, 4_000, 0), SpeakerTurn(5_000, 5_800, 1))
        val out = refiner.refine(phrases, turns, emptyList(), VoicePrinter { voice(0) })
        assertEquals(turns, out)
    }

    @Test
    fun phraseWithoutPrintFollowsItsNeighbour() {
        val long = List(6) { phrase(it * 5_000L, it * 5_000L + 4_000, 0) }
        val phrases = (long + phrase(14_100, 14_500, 0)).sortedBy { it.startMs }
        val prints = arrayOf<FloatArray?>(
            voice(0, 0), voice(1, 1), voice(0, 2), null, voice(1, 3), voice(0, 4), voice(1, 5),
        )
        val decision = refiner.decide(phrases, prints)!!
        assertEquals(decision.labels[2], decision.labels[3])
    }

    @Test
    fun shortDifferentVoiceSurvives() {
        val long = List(6) { phrase(it * 5_000L, it * 5_000L + 4_000, 0) }
        val other = phrase(31_000, 34_000, 1)
        val prints = Array<FloatArray?>(7) { if (it < 6) voice(0, it) else voice(1) }
        assertEquals(2, refiner.decide(long + other, prints)!!.labels.toSet().size)
    }

    @Test
    fun shortSimilarFragmentIsAbsorbed() {
        val long = List(6) { phrase(it * 5_000L, it * 5_000L + 4_000, 0) }
        val other = phrase(31_000, 34_000, 1)
        val near = FloatArray(16).also {
            it[0] = 0.6f
            it[1] = 0.8f
        }
        val prints = Array<FloatArray?>(7) { if (it < 6) voice(0, it) else near }
        assertEquals(1, refiner.decide(long + other, prints)!!.labels.toSet().size)
    }

    @Test
    fun overlapIsTakenOutOfPrints() {
        val words = listOf(
            Word("один", 1_000, 1_400, 0),
            Word("два", 1_500, 1_900, 0),
            Word("три.", 2_000, 3_000, 0),
        )
        val out = refiner.phrases(words, spans(1_500, 1_900)).single()
        assertEquals(spans(1_000, 1_500, 1_900, 3_000), out.clean)
        assertEquals(1_600L, out.cleanMs)
    }

    @Test
    fun overlapsComeFromDifferentClustersOfOneWindow() {
        val line = DiarJournalLine(
            0,
            1_000,
            9_000,
            listOf(DiarTurnRecord(0, 0, 4_000), DiarTurnRecord(1, 3_000, 5_000), DiarTurnRecord(0, 4_500, 6_000)),
            emptyList(),
        )
        assertEquals(spans(3_000, 4_000, 4_500, 5_000), SpeakerRefiner.overlaps(listOf(line)))
    }

    @Test
    fun sentenceEndAndSpeakerChangeCutPhrases() {
        val words = listOf(
            Word("раз", 0, 500, 0),
            Word("два.", 600, 900, 0),
            Word("три", 1_000, 1_400, 0),
            Word("четыре", 1_500, 1_900, 1),
        )
        assertEquals(listOf(0L, 1_000L, 1_500L), refiner.phrases(words, emptyList()).map { it.startMs })
    }

    @Test
    fun turnIsCutBetweenPhrases() {
        val phrases = List(4) { phrase(it * 5_000L, it * 5_000L + 4_000, 0) }
        val turns = listOf(SpeakerTurn(0, 19_500, 0))
        val prints = listOf(voice(0, 0), voice(1, 1), voice(0, 2), voice(1, 3))
        val out = refiner.refine(phrases, turns, emptyList(), VoicePrinter { spans -> prints[(spans.first().startMs / 5_000).toInt()] })
        assertEquals(listOf(0L, 4_500L, 9_500L, 14_500L), out.map { it.startMs })
        assertEquals(19_500L, out.last().endMs)
        assertEquals(out[0].speaker, out[2].speaker)
        assertEquals(out[1].speaker, out[3].speaker)
    }

    @Test
    fun anchorsAreBounded() {
        val small = SpeakerRefiner(RefineParams(maxAnchors = 50))
        val phrases = List(500) { phrase(it * 5_000L, it * 5_000L + 4_000, 0) }
        val prints = Array<FloatArray?>(500) { voice(it % 3, it) }
        assertEquals(3, small.decide(phrases, prints)!!.labels.toSet().size)
    }

    @Test
    fun sameInputGivesSameDecision() {
        val random = Random(5)
        val phrases = List(60) { phrase(it * 5_000L, it * 5_000L + 3_000, random.nextInt(4)) }
        val prints = Array<FloatArray?>(60) { index ->
            val base = voice(index % 3, index)
            FloatArray(16) { base[it] + random.nextFloat() * 0.3f }
        }
        val first = refiner.decide(phrases, prints)!!
        val second = refiner.decide(phrases, prints)!!
        assertTrue(first.labels.contentEquals(second.labels))
        assertEquals(first.fallback, second.fallback)
    }

    /** Слова идут через настоящее слияние: при любом решении текст и порядок не меняются. */
    @Test
    fun wordsSurviveAnyDecision() {
        val random = Random(11)
        repeat(300) {
            val durationMs = 120_000L
            val tokens = ArrayList<AsrToken>()
            var at = 200L
            while (at < durationMs - 2_000) {
                val mark = if (random.nextInt(6) == 0) "." else ""
                tokens += AsrToken(" слово${tokens.size}$mark", at)
                at += 150 + random.nextInt(900)
            }
            val asr = listOf(
                AsrSegment(SpeechSegment(0, 0, durationMs * 16), tokens.joinToString("") { it.text }, tokens),
            )
            val turns = ArrayList<SpeakerTurn>()
            var from = 0L
            while (from < durationMs) {
                val to = minOf(durationMs, from + 500 + random.nextInt(9_000))
                turns += SpeakerTurn(from, to, random.nextInt(3))
                from = to + random.nextInt(600)
            }
            val merger = DefaultMerger(durationMs)
            val phrases = refiner.phrases(merger.assignWords(asr, turns), emptyList())
            val prints = List(phrases.size) { voice(random.nextInt(4), random.nextInt(8)) }
            val refined = refiner.refine(phrases, turns, emptyList(), VoicePrinter { spans ->
                val found = phrases.indexOfFirst { spans.first().startMs >= it.startMs && spans.first().startMs < it.endMs }
                if (found >= 0) prints[found] else null
            })
            val utterances = merger.merge(asr, refined)
            val expected = attachPunctuation(stitchChunks(asr)).map { it.text }
            assertEquals(expected, utterances.flatMap { it.words }.map { it.text })
            assertTrue(utterances.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
            val verdict = DefaultTranscriptValidator().validate(TranscriptEncoder.encode(utterances), durationMs)
            assertTrue(verdict is ValidationResult.Ok)
        }
    }
}
