package ru.stenokot.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun englishWindowKeepsConfirmedItemsOnly() {
        val raw = "T: buy the food\nD 21\nA 22\nD 99\nA 24\nD 21\nQ 25\nA 23\n"
        val extract = parseWindowOutput(raw, WINDOW_EN)
        assertEquals(WindowOutcome.OK, extract.outcome)
        assertEquals("buy the food", extract.topic)
        assertEquals(listOf('D' to 21, 'A' to 22), refs(extract))
        assertEquals(listOf(0, 1), extract.items.map { it.utterance.speaker })
    }

    @Test
    fun foreignTopicIsRejectedAndItemsStay() {
        val extract = parseWindowOutput("T: quarterly tax audit results\nD 21\n", WINDOW_EN)
        assertNull(extract.topic)
        assertEquals(listOf(21), extract.items.map { it.utterance.id })
    }

    @Test
    fun russianWindowDropsShortUtterance() {
        val extract = parseWindowOutput("T: закупаем корм\nD 12\nA 13\nA 14\n", WINDOW_RU)
        assertEquals("закупаем корм", extract.topic)
        assertEquals(listOf('D' to 12, 'A' to 13), refs(extract))
    }

    @Test
    fun itemKindMustBeConfirmedByText() {
        assertEquals(emptyList<Pair<Char, Int>>(), refs(parseWindowOutput("T: закупаем корм\nQ 12\nD 13\n", WINDOW_RU)))
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
    fun topicMustBeQuoteFromWindow() {
        val text = WINDOW_RU.text
        assertTrue(isTopicQuote("закупаем корм", text))
        assertTrue(isTopicQuote("ЗАКУПАЕМ КОРМ у нового поставщика", text))
        assertFalse(isTopicQuote("закупка корма", text))
        assertFalse(isTopicQuote("налоговая проверка результатов", text))
    }

    @Test
    fun wordsFromWindowOutOfOrderAreNotQuote() {
        assertFalse(isTopicQuote("корм закупаем", WINDOW_RU.text))
        assertFalse(isTopicQuote("корм поставщика", WINDOW_RU.text))
    }

    @Test
    fun topicLengthIsChecked() {
        val text = "один два три четыре пять шесть семь восемь"
        assertFalse(isTopicQuote("один", text))
        assertTrue(isTopicQuote("один два три четыре пять шесть", text))
        assertFalse(isTopicQuote("один два три четыре пять шесть семь", text))
    }

    @Test
    fun topicDigitsMustComeFromWindow() {
        val window = "Встреча назначена на 15 ноября"
        assertTrue(isTopicQuote("на 15 ноября", window))
        assertFalse(isTopicQuote("на 16 ноября", window))
    }

    @Test
    fun yoAndCaseDoNotMatter() {
        assertTrue(isTopicQuote("ЁЖИКИ в", "ежики в тумане"))
    }

    @Test
    fun firstValidTopicWins() {
        val extract = parseWindowOutput("T: налоговая проверка\nT: закупаем корм\nT: нового поставщика\nD 12\n", WINDOW_RU)
        assertEquals("закупаем корм", extract.topic)
        assertEquals(listOf(12), extract.items.map { it.utterance.id })
    }

    @Test
    fun atMostThreeItems() {
        val window = LlmWindow(
            0,
            (1..5).map { utterance(it, 0, it * 1000L, it * 1000L + 500, "мы решили закрыть склад номер") },
        )
        assertEquals(3, parseWindowOutput("T: закрыть склад\nD 1\nD 2\nD 3\nD 4\nD 5\n", window).items.size)
    }

    @Test
    fun oneUtteranceGivesOneItem() {
        val window = LlmWindow(0, listOf(utterance(1, 0, 0, 1000, "Решили, что Марина подготовь договор склада.")))
        assertEquals(listOf('D' to 1), refs(parseWindowOutput("T: договор склада\nD 1\nA 1\n", window)))
    }

    @Test
    fun itemTextIsNeverTakenFromModel() {
        val extract = parseWindowOutput("T: закупаем корм\nD 12 подмена текста\nA 13: подмена\nA 13\n", WINDOW_RU)
        assertEquals(listOf(13), extract.items.map { it.utterance.id })
        assertEquals(WINDOW_RU.utterances[1].text, extract.items.single().utterance.text)
    }

    @Test
    fun windowsCarriageReturnsAreTolerated() {
        assertEquals("закупаем корм", parseWindowOutput("T: закупаем корм\r\nD 12\r\n", WINDOW_RU).topic)
    }
}
