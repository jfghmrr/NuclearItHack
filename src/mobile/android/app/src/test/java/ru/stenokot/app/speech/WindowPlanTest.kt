package ru.stenokot.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбиение записи на окна диаризации. */
class WindowPlanTest {
    private val rate = 16000L

    @Test
    fun `короткая запись это одно окно`() {
        val windows = WindowedDiarizer.planWindows(30 * rate)
        assertEquals(1, windows.size)
        assertEquals(30 * rate, windows[0].endSample)
    }

    @Test
    fun `короткий хвост присоединяется к окну`() {
        val windows = WindowedDiarizer.planWindows(320 * rate)
        assertEquals(1, windows.size)
    }

    @Test
    fun `после хвоста больше предела появляется второе окно с нахлёстом`() {
        val windows = WindowedDiarizer.planWindows(340 * rate)
        assertEquals(2, windows.size)
        assertEquals(290 * rate, windows[1].startSample)
        assertEquals(300 * rate, windows[0].endSample)
    }

    @Test
    fun `часовая запись покрыта окнами ограниченной длины`() {
        val total = 3600 * rate
        val windows = WindowedDiarizer.planWindows(total)
        assertEquals(0L, windows.first().startSample)
        assertEquals(total, windows.last().endSample)
        assertTrue(windows.all { it.endSample - it.startSample <= 330 * rate })
        windows.zipWithNext().forEach { (a, b) -> assertEquals(10 * rate, a.endSample - b.startSample) }
    }

    @Test
    fun `участки ответственности идут встык и режут нахлёст посередине`() {
        val windows = WindowedDiarizer.planWindows(1200 * rate)
        assertEquals(0L, windows.first().keepFrom)
        assertEquals(1200 * rate, windows.last().keepTo)
        windows.zipWithNext().forEach { (a, b) ->
            assertEquals(a.keepTo, b.keepFrom)
            assertEquals(a.endSample - 5 * rate, a.keepTo)
        }
    }
}
