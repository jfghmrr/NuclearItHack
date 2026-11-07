package ru.stenokot.app.ui.result

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.stenokot.core.protocol.ProtocolDocument
import ru.stenokot.core.protocol.ProtocolItem
import ru.stenokot.core.protocol.ProtocolSection

class ProtocolContentTest {
    private fun section(title: String, start: Long, end: Long, items: List<ProtocolItem> = emptyList()) =
        ProtocolSection(title, start, end, 1, items)

    private val fallback: (String) -> String = { "Фрагмент $it" }

    @Test
    fun `тема получает заглавную букву`() {
        assertEquals("Полив и ветер", chapterTitle(section("полив и ветер", 0, 1_000), fallback))
    }

    @Test
    fun `глава без темы подписывается интервалом`() {
        assertEquals(
            "Фрагмент 06:20 - 09:30",
            chapterTitle(section("06:20 - 09:30", 380_000, 570_000), fallback),
        )
        assertEquals("Фрагмент 00:00 - 00:01", chapterTitle(section("  ", 0, 1_000), fallback))
    }

    @Test
    fun `тема с цифрами не считается интервалом`() {
        assertEquals("Бюджет 2026", chapterTitle(section("бюджет 2026", 0, 1_000), fallback))
    }

    @Test
    fun `пункты идут по времени через все разделы`() {
        fun item(start: Long) = ProtocolItem("decision", 1, "speaker_1", start, "текст")
        val document = ProtocolDocument(
            sections = listOf(
                section("а", 0, 10, listOf(item(30), item(5))),
                section("б", 10, 20, listOf(item(12))),
            ),
            coveredMs = 20,
            totalMs = 20,
        )
        assertEquals(listOf(5L, 12L, 30L), protocolItems(document).map { it.startMs })
    }

    @Test
    fun `вид пункта разбирается по значениям protocol json`() {
        assertEquals(RibbonMarkKind.DECISION, ribbonKind("decision"))
        assertEquals(RibbonMarkKind.ACTION, ribbonKind("action"))
        assertEquals(RibbonMarkKind.QUESTION, ribbonKind("question"))
    }

    @Test
    fun `номер говорящего берётся из идентификатора`() {
        assertEquals(3, speakerNumber("speaker_3"))
        assertEquals(1, speakerNumber("непонятно"))
    }
}
