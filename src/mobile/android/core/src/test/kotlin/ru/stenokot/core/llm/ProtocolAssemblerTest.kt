package ru.stenokot.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolAssemblerTest {
    private fun result(window: LlmWindow, raw: String) = WindowResult(window, parseWindowOutput(raw, window))

    @Test
    fun itemsAreCopiedVerbatimFromUtterance() {
        val document = ProtocolAssembler.assemble(
            listOf(result(WINDOW_RU, "T: закупка корма\nA 13\nD 12\n")),
            60_000,
        )
        val section = document.sections.single()
        assertEquals("закупка корма", section.title)
        assertEquals(0L, section.startMs)
        assertEquals(8_000L, section.endMs)
        assertEquals(12, section.firstUtterance)
        assertEquals(listOf(12, 13), section.items.map { it.utterance })
        val item = section.items[1]
        assertEquals("action", item.kind)
        assertEquals("speaker_2", item.speakerId)
        assertEquals(3_100L, item.startMs)
        assertEquals("Барсик, поручаю тебе договориться с поставщиком.", item.text)
        assertEquals("decision", section.items[0].kind)
    }

    @Test
    fun questionKindIsMapped() {
        val document = ProtocolAssembler.assemble(listOf(result(WINDOW_EN, "T: buying food\nQ 25\n")), 60_000)
        assertEquals("question", document.sections.single().items.single().kind)
    }

    @Test
    fun rejectedTopicBecomesTimeInterval() {
        val document = ProtocolAssembler.assemble(listOf(result(WINDOW_RU, "T: налоговая проверка\nD 12\n")), 60_000)
        assertEquals("00:00 - 00:08", document.sections.single().title)
        assertEquals(1, document.sections.single().items.size)
    }

    @Test
    fun longRecordingIntervalHasHours() {
        val window = LlmWindow(0, listOf(utterance(1, 0, 3_725_000, 3_730_000, "обычная реплика на русском языке")))
        val document = ProtocolAssembler.assemble(listOf(result(window, "мусор")), 4_000_000)
        assertEquals("1:02:05 - 1:02:10", document.sections.single().title)
    }

    @Test
    fun coverageCountsOverlapOnce() {
        val first = LlmWindow(0, listOf(utterance(1, 0, 0, 10_000, "обычная реплика на русском языке")))
        val second = LlmWindow(1, listOf(utterance(1, 0, 5_000, 20_000, "обычная реплика на русском языке")))
        val third = LlmWindow(2, listOf(utterance(3, 0, 50_000, 60_000, "обычная реплика на русском языке")))
        val document = ProtocolAssembler.assemble(
            listOf(result(third, "x"), result(first, "x"), result(second, "x")),
            100_000,
        )
        assertEquals(30_000L, document.coveredMs)
        assertEquals(100_000L, document.totalMs)
        assertEquals(listOf(0L, 5_000L, 50_000L), document.sections.map { it.startMs })
    }

    @Test
    fun sameItemInOverlappingWindowsAppearsOnce() {
        val shared = utterance(5, 0, 5_000, 6_000, "обычная реплика на русском языке")
        val first = LlmWindow(0, listOf(utterance(4, 0, 0, 4_000, "другая реплика на русском языке"), shared))
        val second = LlmWindow(1, listOf(shared, utterance(6, 1, 7_000, 8_000, "ещё одна реплика на русском")))
        val document = ProtocolAssembler.assemble(listOf(result(first, "D 5\n"), result(second, "D 5\nQ 6\n")), 10_000)
        assertEquals(listOf(5, 6), document.sections.flatMap { it.items }.map { it.utterance })
    }

    @Test
    fun skippedWindowsDoNotCount() {
        val digits = LlmWindow(0, listOf(utterance(1, 0, 0, 5_000, "12 34")))
        val document = ProtocolAssembler.assemble(listOf(result(digits, "T: x\n")), 5_000)
        assertTrue(document.sections.isEmpty())
        assertEquals(0L, document.coveredMs)
    }
}
