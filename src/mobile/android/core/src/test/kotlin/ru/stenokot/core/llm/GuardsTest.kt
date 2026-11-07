package ru.stenokot.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardsTest {
    private fun refs(extract: WindowExtract) = extract.items.map { it.kind.letter to it.utterance.id }

    @Test
    fun scriptDetection() {
        assertEquals(Script.EN, detectScript(WINDOW_EN.utterances[0].text.repeat(3)))
        assertEquals(Script.RU, detectScript(WINDOW_RU.utterances[0].text.repeat(3)))
        assertEquals(Script.MIXED, detectScript("Мы открыли dashboard и merge request в репозитории проекта ok"))
        assertEquals(Script.UNKNOWN, detectScript("12 34 ... ?"))
    }

    @Test
    fun englishWindowKeepsValidItemsOnly() {
        val raw = "T: buying food from supplier\nD 21\nA 22\nD 99\nA 24\nD 21\nQ 25\nA 23\n"
        val extract = parseWindowOutput(raw, WINDOW_EN)
        assertEquals(WindowOutcome.OK, extract.outcome)
        assertEquals("buying food from supplier", extract.topic)
        assertEquals(listOf('D' to 21, 'A' to 22, 'Q' to 25), refs(extract))
        assertEquals(listOf(0, 1, 1), extract.items.map { it.utterance.speaker })
    }

    @Test
    fun foreignTopicIsRejectedAndItemsStay() {
        val extract = parseWindowOutput("T: quarterly tax audit results\nD 21\n", WINDOW_EN)
        assertNull(extract.topic)
        assertEquals(listOf(21), extract.items.map { it.utterance.id })
    }

    @Test
    fun russianWindowDropsShortUtterance() {
        val extract = parseWindowOutput("T: закупка корма\nD 12\nA 13\nA 14\n", WINDOW_RU)
        assertEquals("закупка корма", extract.topic)
        assertEquals(listOf('D' to 12, 'A' to 13), refs(extract))
    }

    @Test
    fun garbageGivesEmptyResultWithoutFailure() {
        val extract = parseWindowOutput("garbage output", WINDOW_RU)
        assertEquals(WindowOutcome.OK, extract.outcome)
        assertNull(extract.topic)
        assertTrue(extract.items.isEmpty())
    }

    @Test
    fun windowOfDigitsIsSkipped() {
        val window = LlmWindow(0, listOf(utterance(1, 0, 0, 1, "12 34")))
        assertEquals(WindowOutcome.SKIPPED_SCRIPT, parseWindowOutput("T: x\nD 1\n", window).outcome)
    }

    @Test
    fun topicWordsAreCheckedByStem() {
        val text = WINDOW_RU.text
        assertNull(checkTopic("закупка корма", text))
        assertNull(checkTopic("закупки кормов поставщик", text))
        assertEquals(TopicRejection.SUPPORT, checkTopic("налоговая проверка результатов", text))
    }

    @Test
    fun shortTopicWordsAreNotCounted() {
        assertNull(checkTopic("да и в", WINDOW_RU.text))
    }

    @Test
    fun topicDigitsMustComeFromWindow() {
        val window = "Встреча назначена на 15 ноября"
        assertNull(checkTopic("встреча 15 ноября", window))
        assertEquals(TopicRejection.DIGITS, checkTopic("встреча 16 ноября", window))
    }

    @Test
    fun yoAndCaseDoNotMatter() {
        assertNull(checkTopic("ЁЖИК", "ежики в тумане"))
    }

    @Test
    fun onlyFirstTopicLineCounts() {
        val extract = parseWindowOutput("T: налоговая проверка\nT: закупка корма\nD 12\n", WINDOW_RU)
        assertNull(extract.topic)
        assertEquals(listOf(12), extract.items.map { it.utterance.id })
    }

    @Test
    fun atMostThreeItems() {
        val window = LlmWindow(
            0,
            (1..5).map { utterance(it, 0, it * 1000L, it * 1000L + 500, "обычная реплика про работу номер") },
        )
        assertEquals(3, parseWindowOutput("T: работа\nD 1\nD 2\nD 3\nD 4\nD 5\n", window).items.size)
    }

    @Test
    fun sameUtteranceWithDifferentKindsIsAllowed() {
        val extract = parseWindowOutput("T: закупка\nD 12\nA 12\n", WINDOW_RU)
        assertEquals(listOf('D' to 12, 'A' to 12), refs(extract))
    }

    @Test
    fun itemTextIsNeverTakenFromModel() {
        val extract = parseWindowOutput("T: закупка\nD 12 подмена текста\nD 13: подмена\nD 13\n", WINDOW_RU)
        assertEquals(listOf(13), extract.items.map { it.utterance.id })
        assertEquals(WINDOW_RU.utterances[1].text, extract.items.single().utterance.text)
    }

    @Test
    fun windowsCarriageReturnsAreTolerated() {
        assertEquals("закупка корма", parseWindowOutput("T: закупка корма\r\nD 12\r\n", WINDOW_RU).topic)
    }
}
