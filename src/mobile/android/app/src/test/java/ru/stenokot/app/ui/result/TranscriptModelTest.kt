package ru.stenokot.app.ui.result

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.Utterance

class TranscriptModelTest {
    private val style = MatchStyle(Color.Black, Color.White)

    private fun utt(id: Int, speaker: Int, start: Long, end: Long, text: String) =
        Utterance(id, speaker, start, end, emptyList(), text)

    private val utterances = listOf(
        utt(10, 0, 1_000, 2_000, "Полив решён."),
        utt(11, 0, 2_000, 3_000, "Ёмкости купим."),
        utt(12, 1, 3_500, 4_000, "Полив завтра."),
        utt(13, 0, 4_500, 5_000, "Договорились."),
    )

    private val model = buildTranscriptModel(utterances, Language.RU)

    private fun overlapping(id: Int, speaker: Int, start: Long, end: Long, overlap: Boolean = true) =
        Utterance(id, speaker, start, end, emptyList(), "Реплика $id", overlap)

    @Test
    fun `наложение называет обоих говорящих`() {
        val rows = buildTranscriptModel(
            listOf(overlapping(1, 0, 0, 2_000), overlapping(2, 2, 1_000, 3_000)),
            Language.RU,
        ).rows
        assertEquals("1 и 3", rows[0].overlapSpeakers)
        assertEquals("1 и 3", rows[1].overlapSpeakers)
    }

    @Test
    fun `трое одновременно перечисляются через запятую`() {
        val rows = buildTranscriptModel(
            listOf(overlapping(1, 0, 0, 3_000), overlapping(2, 1, 500, 2_500), overlapping(3, 2, 1_000, 2_000)),
            Language.RU,
        ).rows
        assertEquals("1, 2 и 3", rows[1].overlapSpeakers)
    }

    @Test
    fun `короткая реплика не прячет длинную перед ней`() {
        val rows = buildTranscriptModel(
            listOf(
                overlapping(1, 0, 0, 10_000),
                overlapping(2, 0, 1_000, 1_500, overlap = false),
                overlapping(3, 1, 5_000, 6_000),
            ),
            Language.RU,
        ).rows
        assertEquals("1 и 2", rows[2].overlapSpeakers)
    }

    @Test
    fun `без собеседника по времени номера не выдумываются`() {
        val rows = buildTranscriptModel(
            listOf(overlapping(1, 0, 0, 1_000), overlapping(2, 1, 2_000, 3_000, overlap = false)),
            Language.RU,
        ).rows
        assertEquals(null, rows[0].overlapSpeakers)
        assertEquals(null, rows[1].overlapSpeakers)
    }

    @Test
    fun `реплики считаются по говорящим`() {
        assertEquals(mapOf(1 to 3, 2 to 1), model.repliesBySpeaker)
    }

    @Test
    fun `текст реплики не меняется`() {
        assertEquals(utterances.map { it.text }, model.rows.map { it.text.text })
    }

    @Test
    fun `говорящие нумеруются с единицы`() {
        assertEquals(listOf(1, 2), model.speakers)
        assertEquals(1, model.rows[0].speaker)
    }

    @Test
    fun `последняя начавшаяся реплика считается активной и в паузе`() {
        assertEquals(-1, model.indexAt(500))
        assertEquals(0, model.indexAt(1_000))
        assertEquals(1, model.indexAt(3_400))
        assertEquals(3, model.indexAt(9_000))
    }

    @Test
    fun `идентификатор ведёт к порядковому номеру`() {
        assertEquals(2, model.indexOfId(12))
        assertEquals(null, model.indexOfId(99))
    }

    @Test
    fun `группа держится на соседстве одного говорящего`() {
        val view = buildTranscriptView(model, "", emptySet(), style)
        assertEquals(listOf(true, false, true, true), view.rows.map { it.startsGroup })
        assertEquals(listOf(false, true, true, true), view.rows.map { it.endsGroup })
        assertFalse(view.narrowed)
    }

    @Test
    fun `фильтр не склеивает реплики, разорванные другим говорящим`() {
        val view = buildTranscriptView(model, "", setOf(1), style)
        assertEquals(listOf(10, 11, 13), view.rows.map { it.row.id })
        assertEquals(listOf(true, false, true), view.rows.map { it.startsGroup })
        assertTrue(view.narrowed)
    }

    @Test
    fun `поиск оставляет реплики с вхождением и не различает регистр и ё`() {
        val view = buildTranscriptView(model, "  полив ", emptySet(), style)
        assertEquals(listOf(10, 12), view.rows.map { it.row.id })
        assertEquals(listOf(1_000L, 3_500L), view.matchedTimesMs.toList())
        assertEquals("Полив решён.", view.rows[0].text.text)
        assertEquals(1, view.rows[0].text.spanStyles.size)
        assertEquals(1, buildTranscriptView(model, "емкости", emptySet(), style).rows.size)
    }

    @Test
    fun `поиск и фильтр работают вместе`() {
        val view = buildTranscriptView(model, "полив", setOf(2), style)
        assertEquals(listOf(12), view.rows.map { it.row.id })
    }

    @Test
    fun `ближайшая позиция учитывает суженный список`() {
        val view = buildTranscriptView(model, "", setOf(2), style)
        assertEquals(0, view.nearestPosition(0))
        assertEquals(0, view.nearestPosition(9_000))
        val empty = buildTranscriptView(model, "нет такого", emptySet(), style)
        assertEquals(-1, empty.nearestPosition(0))
    }

    @Test
    fun `реплики на английском помечаются языком`() {
        val english = buildTranscriptModel(utterances, Language.EN)
        assertEquals("en", english.rows[0].text.spanStyles.single().item.localeList?.get(0)?.language)
        assertTrue(model.rows[0].text.spanStyles.isEmpty())
    }

    @Test
    fun `восьмичасовая запись и сотня говорящих разбираются целиком`() {
        val total = 20_000
        val step = 28_800_000L / total
        val long = List(total) { index ->
            utt(index, index % 100, index * step, index * step + step - 100, "Реплика номер $index про полив.")
        }
        val big = buildTranscriptModel(long, Language.RU)
        assertEquals(100, big.speakers.size)
        assertEquals(100, big.speakers.last())
        assertEquals("7:59:58 - 7:59:59", big.rows.last().time)
        assertEquals(total - 1, big.indexAt(Long.MAX_VALUE))
        val found = buildTranscriptView(big, "номер 19999", emptySet(), style)
        assertEquals(listOf(19_999), found.rows.map { it.row.id })
    }
}
