package ru.stenokot.core.lang

import org.junit.Assert.assertEquals
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

    @Test
    fun noSamplesDefaultToRussian() {
        assertEquals(Language.RU, vote.detect(emptyList()))
    }

    @Test
    fun russianTextIsRussian() {
        assertEquals(Language.RU, vote.detect(chunks(russian, russian, russian)))
    }

    @Test
    fun confidentEnglishIsEnglish() {
        assertEquals(Language.EN, vote.detect(chunks(english, english, english)))
    }

    @Test
    fun englishTermsInRussianSpeechStayRussian() {
        val mixed = "Мы открыли dashboard, потом merge request и pull request в GitHub, и это не сработало."
        assertEquals(Language.RU, vote.detect(chunks(mixed, mixed, mixed)))
    }

    @Test
    fun latinScriptWithoutServiceWordsStaysRussian() {
        assertEquals(Language.RU, vote.detect(chunks("dashboard pipeline merge", "backend frontend", "kubernetes")))
    }

    @Test
    fun oneEnglishChunkOfThreeStaysRussian() {
        assertEquals(Language.RU, vote.detect(chunks(english, russian, russian)))
    }

    @Test
    fun twoEnglishChunksOfThreeAreEnglish() {
        assertEquals(Language.EN, vote.detect(chunks(english, english, russian)))
    }

    @Test
    fun emptyTextsStayRussian() {
        assertEquals(Language.RU, vote.detect(chunks("", "", "")))
    }

    @Test
    fun tallyReportsVotes() {
        val tally = vote.tally(chunks(english, russian))
        assertEquals(2, tally.chunks)
        assertEquals(1, tally.enChunks)
        assertEquals(Language.RU, tally.language)
    }
}
