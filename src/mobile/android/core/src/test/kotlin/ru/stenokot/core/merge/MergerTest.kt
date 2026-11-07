package ru.stenokot.core.merge

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.asr.isMarkOnly
import ru.stenokot.core.contract.DefaultTranscriptValidator
import ru.stenokot.core.contract.TranscriptEncoder
import ru.stenokot.core.contract.ValidationResult
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.AsrToken
import ru.stenokot.core.model.SpeakerTurn
import ru.stenokot.core.model.SpeechSegment
import ru.stenokot.core.model.Utterance

class MergerTest {
    private fun segment(index: Int, startMs: Long, endMs: Long, tokens: List<AsrToken>) =
        AsrSegment(SpeechSegment(index, startMs * 16, endMs * 16), tokens.joinToString("") { it.text }, tokens)

    private fun token(text: String, ms: Long) = AsrToken(text, ms)

    /** Слова с равным шагом: каждое слово занимает [stepMs], знаки идут отдельными токенами. */
    private fun spoken(text: String, startMs: Long, stepMs: Long): List<AsrToken> {
        val out = ArrayList<AsrToken>()
        var at = startMs
        for (word in text.split(" ")) {
            val body = word.trimEnd('.', ',', '?', '!')
            out += token(" $body", at)
            val mark = word.removePrefix(body)
            if (mark.isNotEmpty()) out += token(mark, at + stepMs / 2)
            at += stepMs
        }
        return out
    }

    private fun merge(
        durationMs: Long,
        tokens: List<AsrToken>,
        turns: List<SpeakerTurn>,
        params: MergeParams = MergeParams(minSpeakerMinMs = 0, minSpeakerMaxMs = 0),
    ): List<Utterance> =
        DefaultMerger(durationMs, params).merge(listOf(segment(0, 0, durationMs, tokens)), turns)

    private fun labels(utterances: List<Utterance>) = utterances.map { "${it.speaker}:${it.text}" }

    @Test
    fun emptyInputGivesEmptyResult() {
        assertTrue(DefaultMerger(1000).merge(emptyList(), emptyList()).isEmpty())
        assertTrue(DefaultMerger(0).merge(listOf(segment(0, 0, 0, spoken("да", 0, 100))), emptyList()).isEmpty())
    }

    @Test
    fun noDiarizationGivesSingleSpeaker() {
        val out = merge(5_000, spoken("один два три", 0, 400), emptyList())
        assertEquals(listOf("0:один два три"), labels(out))
    }

    @Test
    fun speakerChangeSplitsAndNumbersByFirstAppearance() {
        val tokens = spoken("Добрый день.", 0, 500) + spoken("Здравствуйте, коллеги.", 1_500, 700)
        val turns = listOf(SpeakerTurn(0, 1_400, 7), SpeakerTurn(1_450, 3_100, 3))
        val out = merge(4_000, tokens, turns)
        assertEquals(listOf("0:Добрый день.", "1:Здравствуйте, коллеги."), labels(out))
        assertEquals(listOf(0, 1), out.map { it.speaker })
        assertEquals(listOf(1, 2), out.map { it.id })
    }

    @Test
    fun longPauseSplitsSameSpeaker() {
        val tokens = spoken("раз два", 0, 300) + spoken("три четыре", 3_000, 300)
        val out = merge(5_000, tokens, listOf(SpeakerTurn(0, 5_000, 0)))
        assertEquals(listOf("0:раз два", "0:три четыре"), labels(out))
    }

    @Test
    fun longUtteranceSplitsAtSentenceEnd() {
        val first = "слово ".repeat(49).trim() + " конец."
        val second = "слово ".repeat(55).trim()
        val out = merge(60_000, spoken("$first $second", 0, 500), listOf(SpeakerTurn(0, 60_000, 0)))
        assertEquals(2, out.size)
        assertTrue(out[0].text.endsWith("конец."))
        assertTrue(out.all { it.endMs - it.startMs <= 30_000 })
    }

    @Test
    fun longUtteranceWithoutSentenceEndStaysWithinLimit() {
        val out = merge(100_000, spoken("слово ".repeat(180).trim(), 0, 500), listOf(SpeakerTurn(0, 100_000, 0)))
        assertTrue(out.size >= 3)
        assertTrue(out.dropLast(1).all { it.words.last().endMs - it.startMs <= 30_000 })
    }

    @Test
    fun shortIslandIsAbsorbed() {
        val tokens = spoken("это длинная реплика первого говорящего без перерыва совсем", 0, 400)
        val turns = listOf(
            SpeakerTurn(0, 1_200, 0),
            SpeakerTurn(1_200, 2_000, 1),
            SpeakerTurn(2_000, 3_200, 0),
            SpeakerTurn(10_000, 13_000, 1),
        )
        assertEquals(1, merge(14_000, tokens, turns).size)
    }

    @Test
    fun backchannelIslandSurvives() {
        val tokens = spoken("это длинная реплика", 0, 400) + spoken("да", 1_200, 400) +
            spoken("первого говорящего", 1_600, 400)
        val turns = listOf(
            SpeakerTurn(0, 1_200, 0),
            SpeakerTurn(1_200, 1_600, 1),
            SpeakerTurn(1_600, 2_400, 0),
        )
        assertEquals(
            listOf("0:это длинная реплика", "1:да", "0:первого говорящего"),
            labels(merge(3_000, tokens, turns)),
        )
    }

    @Test
    fun backchannelWithPunctuationAndCaseSurvives() {
        val tokens = spoken("это длинная реплика", 0, 400) + spoken("Да.", 1_200, 400) +
            spoken("первого говорящего", 1_600, 400)
        val turns = listOf(SpeakerTurn(0, 1_200, 0), SpeakerTurn(1_200, 1_600, 1), SpeakerTurn(1_600, 2_400, 0))
        assertEquals(3, merge(3_000, tokens, turns).size)
    }

    @Test
    fun backchannelOnShortSegmentIsAbsorbed() {
        val tokens = spoken("это длинная реплика", 0, 400) + spoken("да", 1_200, 400) +
            spoken("первого говорящего", 1_600, 400)
        val turns = listOf(
            SpeakerTurn(0, 1_230, 0),
            SpeakerTurn(1_230, 1_400, 1),
            SpeakerTurn(1_400, 2_400, 0),
        )
        assertEquals(1, merge(3_000, tokens, turns).size)
    }

    @Test
    fun boundaryMovesToSentenceEndWhenVoteIsWeak() {
        val tokens = spoken("Мы закончили. Теперь", 0, 400) + spoken("дальше пойдём", 1_200, 400)
        val turns = listOf(
            SpeakerTurn(0, 1_000, 0),
            SpeakerTurn(860, 1_260, 1),
            SpeakerTurn(1_200, 2_400, 1),
        )
        val out = merge(3_000, tokens, turns)
        assertEquals(listOf("0:Мы закончили.", "1:Теперь дальше пойдём"), labels(out))
    }

    @Test
    fun boundaryMovesForwardToSentenceEnd() {
        val tokens = spoken("Итак мы", 0, 400) + spoken("закончили. Спасибо всем", 800, 400)
        val turns = listOf(
            SpeakerTurn(0, 1_000, 0),
            SpeakerTurn(800, 2_000, 1),
        )
        val out = merge(3_000, tokens, turns)
        assertEquals(listOf("0:Итак мы закончили.", "1:Спасибо всем"), labels(out))
    }

    @Test
    fun confidentBoundaryDoesNotMove() {
        val tokens = spoken("Мы закончили. Теперь", 0, 400) + spoken("дальше пойдём", 1_200, 400)
        val turns = listOf(SpeakerTurn(0, 1_200, 0), SpeakerTurn(1_200, 2_400, 1))
        val out = merge(3_000, tokens, turns)
        assertEquals(listOf("0:Мы закончили. Теперь", "1:дальше пойдём"), labels(out))
    }

    @Test
    fun smallClusterIsAbsorbed() {
        val tokens = spoken("это первая реплика говорящего", 0, 400) + spoken("вторая реплика другого человека", 1_600, 400)
        val turns = listOf(SpeakerTurn(0, 1_600, 0), SpeakerTurn(1_600, 3_200, 1), SpeakerTurn(3_300, 30_000, 0))
        val out = merge(31_000, tokens, turns, MergeParams())
        assertEquals(setOf(0), out.map { it.speaker }.toSet())
    }

    @Test
    fun keeperSurvivesWhenEverySpeakerIsSmall() {
        val tokens = spoken("раз два", 0, 300) + spoken("три четыре пять", 700, 300)
        val turns = listOf(SpeakerTurn(0, 600, 0), SpeakerTurn(700, 1_600, 1))
        val out = merge(2_000, tokens, turns, MergeParams())
        assertEquals(1, out.size)
        assertEquals("раз два три четыре пять", out[0].text)
    }

    @Test
    fun overlapIsMarkedOnlyInServiceFields() {
        val tokens = spoken("вместе говорим сейчас", 0, 400)
        val turns = listOf(SpeakerTurn(0, 1_200, 0), SpeakerTurn(0, 1_200, 1))
        val out = merge(2_000, tokens, turns)
        assertTrue(out.single().overlap)
        assertFalse(String(TranscriptEncoder.encode(out), Charsets.UTF_8).contains("overlap"))
    }

    @Test
    fun endUsesSpeakerAndChunkEnd() {
        val tokens = spoken("раз два", 1_000, 300)
        val merger = DefaultMerger(10_000, MergeParams(minSpeakerMinMs = 0, minSpeakerMaxMs = 0))
        val out = merger.merge(
            listOf(segment(0, 800, 3_000, tokens)),
            listOf(SpeakerTurn(900, 5_000, 0)),
        )
        assertEquals(1_000L, out.single().startMs)
        assertEquals(3_000L, out.single().endMs)
        val inside = merger.merge(
            listOf(segment(0, 800, 3_000, tokens)),
            listOf(SpeakerTurn(900, 2_000, 0)),
        )
        assertEquals(2_000L, inside.single().endMs)
    }

    @Test
    fun endDoesNotCrossNextUtterance() {
        val tokens = spoken("раз", 0, 300) + spoken("два", 1_000, 300)
        val turns = listOf(SpeakerTurn(0, 5_000, 0), SpeakerTurn(990, 5_000, 1))
        val out = merge(6_000, tokens, turns, MergeParams(minSpeakerMinMs = 0, minSpeakerMaxMs = 0, snapWords = 0))
        assertTrue(out.size >= 1)
        out.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs <= maxOf(b.startMs, a.words.last().endMs)) }
    }

    @Test
    fun i7EncoderHasOnlyContractKeys() {
        val text = String(TranscriptEncoder.encode(merge(2_000, spoken("да", 0, 300), emptyList())), Charsets.UTF_8)
        val keys = Regex("\"([a-z_]+)\":").findAll(text).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("transcript", "speaker_id", "start_ms", "end_ms", "text"), keys)
    }

    @Test
    fun i8MarkTravelsWithItsWord() {
        val tokens = spoken("да, конечно", 0, 500) + spoken("именно так", 1_000, 500)
        val out = merge(3_000, tokens, listOf(SpeakerTurn(0, 1_000, 0), SpeakerTurn(1_000, 2_000, 1)))
        assertTrue(out[0].text.contains("да,"))
    }

    @Test
    fun i9MarkOnlyChunkAttachesToPreviousChunk() {
        val merger = DefaultMerger(10_000)
        val out = merger.merge(
            listOf(
                segment(0, 0, 3_000, spoken("да", 100, 300)),
                segment(1, 5_000, 6_000, listOf(token(" .", 5_100))),
            ),
            emptyList(),
        )
        assertEquals("да.", out.single().text)
    }

    @Test
    fun i10VoteDoesNotDependOnMarkLength() {
        val turns = listOf(SpeakerTurn(0, 700, 0), SpeakerTurn(700, 3_000, 1))
        val plain = listOf(token(" слово", 500), token(" дальше", 1_500))
        val marked = listOf(token(" слово", 500), token("...", 560), token("»", 600), token(" дальше", 1_500))
        val first = merge(4_000, plain, turns)
        val second = merge(4_000, marked, turns)
        assertEquals(first.map { it.speaker }, second.map { it.speaker })
        assertEquals(first.map { it.startMs }, second.map { it.startMs })
    }

    @Test
    fun i11ConfidentWordsNeverMoveProperty() {
        val random = Random(11)
        repeat(200) {
            val count = random.nextInt(4, 30)
            val text = List(count) { i -> if (random.nextInt(4) == 0) "слово$i." else "слово$i" }
            val tokens = spoken(text.joinToString(" "), 0, 500)
            val change = random.nextInt(1, count - 1)
            val turns = listOf(SpeakerTurn(0, change * 500L, 0), SpeakerTurn(change * 500L, count * 500L, 1))
            val params = MergeParams(minSpeakerMinMs = 0, minSpeakerMaxMs = 0, islandWords = 0, islandMs = 0)
            val out = merge(count * 500L + 500, tokens, turns, params)
            assertEquals(text.take(change).joinToString(" "), out[0].text)
        }
    }

    private data class Case(val duration: Long, val asr: List<AsrSegment>, val turns: List<SpeakerTurn>)

    private fun randomCase(random: Random): Case {
        val vocabulary = listOf(
            " да", " нет", " слово", " при", "вет", " ну", " кто", "-", "то", ",", ".", "?", "!", " —", " «", "»",
            " ...", " ₽", " 12", "%", "<unk>", " ", " Москва", " очень", " длинноесловоизкучибукв",
        )
        val duration = random.nextLong(2_000, 90_000)
        val chunks = ArrayList<AsrSegment>()
        var at = 0L
        var index = 0
        while (at < duration - 500) {
            val end = minOf(duration, at + random.nextLong(500, 22_000))
            val tokens = ArrayList<AsrToken>()
            var t = at + random.nextLong(0, 200) / 40 * 40
            while (t < end) {
                tokens += AsrToken(vocabulary.random(random), t)
                t += 40L * random.nextInt(0, 12)
            }
            chunks += segment(index++, at, end, tokens)
            at = end + if (random.nextBoolean()) 0 else random.nextLong(0, 4_000)
        }
        val speakers = random.nextInt(1, 9)
        val turns = List(random.nextInt(0, 60)) {
            val start = random.nextLong(-200, duration)
            SpeakerTurn(start, start + random.nextLong(-50, 8_000), random.nextInt(-1, speakers))
        }
        return Case(duration, chunks, turns)
    }

    private fun letters(text: String) = text.filter(Char::isLetterOrDigit)

    private fun expectedText(case: Case): String =
        case.asr.flatMap { it.tokens }.filter { it.text != "<unk>" }.joinToString("") { it.text }

    @Test
    fun invariantsHoldOnRandomInputProperty() {
        val random = Random(20261107)
        val validator = DefaultTranscriptValidator()
        repeat(1_500) { iteration ->
            val case = randomCase(random)
            for (params in listOf(MergeParams(), MergeParams(minSpeakerMinMs = 0, minSpeakerMaxMs = 0))) {
                val out = DefaultMerger(case.duration, params).merge(case.asr, case.turns)
                val text = out.joinToString(" ") { it.text }
                assertEquals("И1 $iteration", letters(expectedText(case)), letters(text))
                val expectedMarks = expectedText(case).filterNot { it.isLetterOrDigit() || it == ' ' }
                if (letters(text).isNotEmpty()) {
                    assertEquals("И8 $iteration", expectedMarks.toList().sorted(), text.filterNot { it.isLetterOrDigit() || it == ' ' }.toList().sorted())
                }
                out.forEach { u ->
                    assertTrue("И2", 0 <= u.startMs && u.startMs < u.endMs && u.endMs <= case.duration + 50)
                    assertTrue("И2", u.text.isNotBlank())
                    assertTrue("длина", u.endMs - u.startMs <= params.maxUtteranceMs + params.maxVoteMs)
                    assertEquals(u.words.joinToString(" ") { it.text }, u.text)
                    assertTrue("И9", !isMarkOnly(u.text))
                    assertTrue("И9", u.words.none { isMarkOnly(it.text) })
                    assertTrue(u.words.all { it.speaker == u.speaker })
                }
                assertEquals("И3", out.map { it.startMs }.sorted(), out.map { it.startMs })
                val starts = out.flatMap { it.words }.map { it.startMs }
                assertEquals("И3", starts.sorted(), starts)
                assertEquals("id", (1..out.size).toList(), out.map { it.id })
                var seen = -1
                out.forEach { u ->
                    assertTrue("И5", u.speaker <= seen + 1)
                    seen = maxOf(seen, u.speaker)
                }
                assertEquals(
                    ValidationResult.Ok,
                    validator.validate(TranscriptEncoder.encode(out), case.duration),
                )
                assertEquals(
                    "И6",
                    out,
                    DefaultMerger(case.duration, params).merge(case.asr, case.turns.shuffled(random)),
                )
            }
        }
    }

    @Test
    fun textOfWordsIsNeverChangedProperty() {
        val random = Random(5)
        repeat(300) {
            val tokens = List(random.nextInt(1, 200)) { i -> AsrToken(" слово$i", i * 300L) }
            val duration = tokens.size * 300L + 100
            val turns = List(random.nextInt(1, 40)) {
                val start = random.nextLong(0, duration)
                SpeakerTurn(start, start + random.nextLong(100, 5_000), random.nextInt(0, 6))
            }
            val out = DefaultMerger(duration).merge(listOf(segment(0, 0, duration, tokens)), turns)
            assertEquals("И4", tokens.map { it.text.trim() }, out.flatMap { it.words }.map { it.text })
        }
    }

    @Test
    fun largeInputIsFast() {
        val tokens = List(60_000) { i -> AsrToken(" слово", i * 300L) }
        val duration = 18_000_100L
        val turns = List(6_000) { i -> SpeakerTurn(i * 3_000L, i * 3_000L + 2_900, i % 4) }
        val started = System.nanoTime()
        val out = DefaultMerger(duration).merge(listOf(segment(0, 0, duration, tokens)), turns)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(out.isNotEmpty())
        assertTrue("$ms ms", ms < 2_000)
    }
}
