package ru.stenokot.app.ui.result

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RibbonTest {
    private fun span(speaker: Int, start: Long, end: Long) = RibbonSpan(speaker, start, end)

    @Test
    fun `соседние реплики одного говорящего сливаются`() {
        val model = buildRibbon(
            spans = listOf(span(1, 0, 100), span(1, 100, 200), span(1, 500, 600)),
            moments = emptyList(),
            speakers = listOf(1),
            durationMs = 1_000,
            widthPx = 100,
        )
        assertArrayEquals(floatArrayOf(0f, 20f, 50f, 60f), model.lanes[0].runs, 0f)
    }

    @Test
    fun `говорящие раскладываются по своим дорожкам в заданном порядке`() {
        val model = buildRibbon(
            spans = listOf(span(2, 0, 500), span(1, 500, 1000)),
            moments = emptyList(),
            speakers = listOf(1, 2),
            durationMs = 1_000,
            widthPx = 10,
        )
        assertEquals(listOf(1, 2), model.lanes.map { it.speaker })
        assertArrayEquals(floatArrayOf(5f, 10f), model.lanes[0].runs, 0f)
        assertArrayEquals(floatArrayOf(0f, 5f), model.lanes[1].runs, 0f)
    }

    @Test
    fun `короткая реплика занимает хотя бы один пиксель и не выходит за ленту`() {
        val model = buildRibbon(
            spans = listOf(span(1, 999, 1_000)),
            moments = emptyList(),
            speakers = listOf(1),
            durationMs = 1_000_000,
            widthPx = 100,
        )
        assertEquals(2, model.lanes[0].runs.size)
        assertTrue(model.lanes[0].runs[1] > model.lanes[0].runs[0])

        val edge = buildRibbon(listOf(span(1, 999, 1_000)), emptyList(), listOf(1), 1_000, 100)
        assertTrue(edge.lanes[0].runs[1] <= 100f)
        assertTrue(edge.lanes[0].runs[1] > edge.lanes[0].runs[0])
    }

    @Test
    fun `размер ленты зависит от ширины, а не от числа реплик`() {
        val spans = List(100_000) { index -> span(1, index * 100L, index * 100L + 80) }
        val model = buildRibbon(spans, emptyList(), listOf(1), 10_000_000, 400)
        assertTrue(model.lanes[0].runs.size <= 800)
    }

    @Test
    fun `близкие метки протокола слипаются`() {
        val model = buildRibbon(
            spans = emptyList(),
            moments = listOf(
                RibbonMoment(0, RibbonMarkKind.DECISION),
                RibbonMoment(10, RibbonMarkKind.ACTION),
                RibbonMoment(500, RibbonMarkKind.QUESTION),
            ),
            speakers = emptyList(),
            durationMs = 1_000,
            widthPx = 100,
        )
        assertEquals(listOf(RibbonMarkKind.DECISION, RibbonMarkKind.QUESTION), model.marks.map { it.kind })
    }

    @Test
    fun `нулевая длительность или ширина не ломают построение`() {
        assertEquals(0, buildRibbon(listOf(span(1, 0, 10)), emptyList(), listOf(1), 0, 100).lanes.size)
        assertEquals(0, buildRibbon(listOf(span(1, 0, 10)), emptyList(), listOf(1), 100, 0).lanes.size)
    }

    @Test
    fun `вхождения поиска сливаются в занятые пиксели`() {
        val ticks = bucketTicks(longArrayOf(0, 1, 2, 500, 999), 1_000, 10)
        assertArrayEquals(floatArrayOf(0f, 5f, 9f), ticks, 0f)
    }

    @Test
    fun `время по точке ограничено длительностью`() {
        assertEquals(500, timeAtX(50f, 100f, 1_000))
        assertEquals(0, timeAtX(-5f, 100f, 1_000))
        assertEquals(1_000, timeAtX(500f, 100f, 1_000))
    }

    private fun lanes(speakers: Int, height: Float = 58f) =
        laneLayout(speakers, top = 10f, heightPx = height, gapWide = 5f, gapTight = 3f, minThicknessPx = 1f, maxThicknessPx = 21f)

    @Test
    fun `дорожки всегда укладываются в высоту и толщина положительна`() {
        for (speakers in listOf(0, 1, 2, 3, 4, 5, 8, 9, 12, 20, 30, 57, 58, 59, 100, 500)) {
            val layout = lanes(speakers)
            assertTrue("толщина при $speakers", layout.thickness >= 1f)
            val last = layout.centerY(layout.lanes - 1) + layout.thickness / 2f
            assertTrue("низ при $speakers: $last", last <= 10f + 58f + 0.01f)
            assertTrue("верх при $speakers", layout.centerY(0) - layout.thickness / 2f >= 10f - 0.01f)
        }
    }

    @Test
    fun `говорящий получает свою дорожку, пока они есть`() {
        val layout = lanes(30)
        assertEquals(30, layout.lanes)
        assertEquals(29, layout.laneOf(29))
        assertEquals(0f, layout.gap, 0f)
    }

    @Test
    fun `когда дорожки кончаются, говорящие делят их по кругу`() {
        val layout = lanes(500)
        assertEquals(58, layout.lanes)
        assertEquals(0, layout.laneOf(58))
        assertEquals(layout.centerY(1), layout.centerY(59), 0f)
    }

    @Test
    fun `много говорящих не теряются при построении ленты`() {
        val speakers = (1..64).toList()
        val spans = speakers.map { span(it, it * 1_000L, it * 1_000L + 500) }
        val model = buildRibbon(spans, emptyList(), speakers, 100_000, 400)
        assertEquals(64, model.lanes.size)
        assertTrue(model.lanes.all { it.runs.isNotEmpty() })
    }
}
