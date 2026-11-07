package ru.stenokot.core.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.SpeechSegment

class LanguageVoteTest {
    private val vote = LanguageVote()

    private fun chunks(vararg texts: String) =
        texts.mapIndexed { i, text -> AsrSegment(SpeechSegment(i, 0, 1), text, emptyList()) }

    private val russian = "Ну вот, мы и договорились, что это будет не в пятницу, а в понедельник."
    private val english = "So we buy the food from the new supplier and it is what they said to you."

    /** Настоящий вывод русской модели на английской речи: латиница без служебных слов. */
    private val garbled = "Ohemember You Wann It thishash Kimipenues lefty intrututu disky name David Hivas"

    @Test
    fun almostSilentRecognizerAsksSecondOpinion() {
        val long = AsrSegment(SpeechSegment(0, 0, 320_000), "", emptyList())
        val word = AsrSegment(SpeechSegment(1, 320_000, 640_000), "Витамины.", emptyList())
        assertTrue(vote.needsSecondOpinion(listOf(long, word, long)))
    }

    @Test
    fun normalTextNeedsNoSecondOpinion() {
        val spoken = AsrSegment(SpeechSegment(0, 0, 320_000), russian, emptyList())
        assertFalse(vote.needsSecondOpinion(listOf(spoken)))
    }

    @Test
    fun shortProbesNeedNoSecondOpinion() {
        val quiet = AsrSegment(SpeechSegment(0, 0, 16_000), "", emptyList())
        assertFalse(vote.needsSecondOpinion(listOf(quiet)))
    }

    @Test
    fun denseEnglishTextConfirmsEnglish() {
        val spoken = AsrSegment(SpeechSegment(0, 0, 160_000), english + " " + english, emptyList())
        assertEquals(Language.EN, vote.secondOpinion(listOf(spoken)))
    }

    @Test
    fun sparseWordsKeepRussian() {
        val noise = AsrSegment(SpeechSegment(0, 0, 320_000), "the music oh", emptyList())
        assertEquals(Language.RU, vote.secondOpinion(listOf(noise)))
    }

    @Test
    fun noSamplesDefaultToRussian() {
        assertEquals(Language.RU, vote.detect(emptyList()))
        assertFalse(vote.settled(emptyList()))
    }

    @Test
    fun russianTextIsRussian() {
        assertEquals(Language.RU, vote.detect(chunks(russian, russian, russian)))
        assertTrue(vote.settled(chunks(russian, russian, russian)))
    }

    @Test
    fun confidentEnglishIsEnglish() {
        assertEquals(Language.EN, vote.detect(chunks(english, english, english)))
        assertTrue(vote.settled(chunks(english, english, english)))
    }

    @Test
    fun garbledLatinWithoutServiceWordsIsEnglish() {
        assertEquals(Language.EN, vote.detect(chunks(garbled, garbled, garbled)))
        assertTrue(vote.settled(chunks(garbled, garbled, garbled)))
    }

    @Test
    fun englishChunksSurviveEmptyProbes() {
        assertEquals(Language.EN, vote.detect(chunks("", english, "")))
    }

    @Test
    fun englishTermsInRussianSpeechStayRussian() {
        val mixed = "Мы открыли dashboard, потом merge request и pull request в GitHub, и это не сработало."
        assertEquals(Language.RU, vote.detect(chunks(mixed, mixed, mixed)))
    }

    @Test
    fun cyrillicInsideEnglishChunksDoesNotFlipTheFile() {
        val slip = "Персон black Hammaside vechtems kill by other black people the white"
        assertEquals(Language.EN, vote.detect(chunks(slip, garbled, garbled)))
    }

    @Test
    fun oneEnglishChunkOfThreeStaysRussian() {
        assertEquals(Language.RU, vote.detect(chunks(english, russian, russian)))
    }

    @Test
    fun evenSplitAsksForMoreProbes() {
        assertFalse(vote.settled(chunks(russian, english)))
    }

    @Test
    fun tieStaysRussian() {
        assertEquals(Language.RU, vote.detect(chunks("один два", "three four")))
    }

    @Test
    fun fewWordsAskForMoreProbes() {
        assertEquals(Language.RU, vote.detect(chunks("Да, хорошо")))
        assertFalse(vote.settled(chunks("Да, хорошо")))
    }

    @Test
    fun emptyTextsStayRussianAndAskForMoreProbes() {
        assertEquals(Language.RU, vote.detect(chunks("", "", "")))
        assertFalse(vote.settled(chunks("", "", "")))
    }

    @Test
    fun tallyReportsVotes() {
        val tally = vote.tally(chunks("один two three", "четыре"))
        assertEquals(2, tally.cyrillic)
        assertEquals(2, tally.latin)
        assertEquals(2, tally.chunks)
        assertEquals(Language.RU, tally.language)
    }
}
