package ru.stenokot.core.diar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.asr.attachPunctuation
import ru.stenokot.core.asr.stitchChunks
import ru.stenokot.core.merge.DefaultMerger
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.AsrToken
import ru.stenokot.core.model.SpeakerTurn
import ru.stenokot.core.model.SpeechSegment

/** Тихие ответы после тире: запись с короткими ответами дальнего собеседника. */
class QuietRepliesTest {
    private val durationMs = 24_000L

    private fun asr(vararg words: Pair<String, Long>): List<AsrSegment> {
        val tokens = words.map { AsrToken(" ${it.first}", it.second) }
        return listOf(AsrSegment(SpeechSegment(0, 0, durationMs * 16), tokens.joinToString("") { it.text }, tokens))
    }

    private val dialogue = asr(
        "—" to 1_600, "Ты" to 1_700, "здесь?" to 1_900, "—" to 2_240, "Да." to 2_984, "—" to 3_200,
        "Как" to 4_240, "у" to 4_500, "тебя" to 4_600, "дела?" to 4_900, "—" to 5_200, "Нормально." to 5_840, "—" to 6_300,
        "Что" to 10_400, "делаешь?" to 10_700, "—" to 11_000, "Сижу." to 11_600, "—" to 11_900,
    )

    private val oneSpeaker = listOf(SpeakerTurn(1_500, 12_200, 0))

    /** Ответы на 15 дБ тише вопросов. */
    private val quietAnswers = LevelMeter { span ->
        if (span.startMs in listOf(2_984L, 5_840L, 11_600L)) -40f else -25f
    }

    private fun speakersOfWords(turns: List<SpeakerTurn>): Map<String, Int> =
        DefaultMerger(durationMs).merge(dialogue, turns).flatMap { it.words }.associate { it.text to it.speaker }

    @Test
    fun quietRepliesAfterDashGoToAnotherSpeaker() {
        val words = DefaultMerger(durationMs).assignWords(dialogue, oneSpeaker)
        val turns = QuietReplies().apply(words, oneSpeaker, quietAnswers)
        val speakers = speakersOfWords(turns)
        val answers = listOf("Да. —", "Нормально. —", "Сижу. —").map { speakers.getValue(it) }.toSet()
        val questions = listOf("здесь? —", "дела? —", "делаешь? —").map { speakers.getValue(it) }.toSet()
        assertEquals(1, answers.size)
        assertEquals(1, questions.size)
        assertTrue(answers.single() != questions.single())
    }

    @Test
    fun evenLevelsChangeNothing() {
        val words = DefaultMerger(durationMs).assignWords(dialogue, oneSpeaker)
        assertEquals(oneSpeaker, QuietReplies().apply(words, oneSpeaker, LevelMeter { -25f }))
    }

    @Test
    fun quietWordsWithoutDashStay() {
        val plain = asr("Как" to 1_000, "дела" to 1_300, "нормально" to 2_400, "что" to 4_000, "делаешь" to 4_300)
        val words = DefaultMerger(durationMs).assignWords(plain, oneSpeaker)
        val meter = LevelMeter { span -> if (span.startMs == 2_400L) -40f else -25f }
        assertEquals(oneSpeaker, QuietReplies().apply(words, oneSpeaker, meter))
    }

    @Test
    fun withoutQuieterSpeakerNothingMoves() {
        val two = listOf(SpeakerTurn(1_500, 6_500, 0), SpeakerTurn(10_000, 12_200, 1))
        val words = DefaultMerger(durationMs).assignWords(dialogue, two)
        val meter = LevelMeter { span -> if (span.startMs == 2_984L || span.startMs == 5_840L) -40f else -25f }
        assertEquals(two, QuietReplies().apply(words, two, meter))
    }

    @Test
    fun quieterExistingSpeakerTakesTheReplies() {
        val two = listOf(SpeakerTurn(1_500, 6_500, 0), SpeakerTurn(10_000, 12_200, 1))
        val words = DefaultMerger(durationMs).assignWords(dialogue, two)
        val meter = LevelMeter { span -> if (span.startMs == 2_984L || span.startMs >= 10_000L) -40f else -25f }
        val speakers = DefaultMerger(durationMs).merge(dialogue, QuietReplies().apply(words, two, meter))
            .flatMap { it.words }.associate { it.text to it.speaker }
        assertEquals(speakers.getValue("делаешь? —"), speakers.getValue("Да. —"))
    }

    @Test
    fun wordsStayTheSame() {
        val words = DefaultMerger(durationMs).assignWords(dialogue, oneSpeaker)
        val turns = QuietReplies().apply(words, oneSpeaker, quietAnswers)
        val expected = attachPunctuation(stitchChunks(dialogue)).map { it.text }
        assertEquals(expected, DefaultMerger(durationMs).merge(dialogue, turns).flatMap { it.words }.map { it.text })
    }
}
