package ru.stenokot.app.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test

class PluralsTest {
    private fun forms(n: Long) = pluralRu(n, "one", "few", "many")

    @Test
    fun russianRules() {
        listOf(1L, 21L, 101L, 1001L).forEach { assertEquals("one", forms(it)) }
        listOf(2L, 3L, 4L, 22L, 34L, 102L).forEach { assertEquals("few", forms(it)) }
        listOf(0L, 5L, 9L, 10L, 11L, 12L, 13L, 14L, 19L, 20L, 100L, 111L).forEach { assertEquals("many", forms(it)) }
    }

    @Test
    fun genitiveAfterPrepositionFrom() {
        assertEquals("фрагмента", genitiveAfterOf(1, "фрагмента", "фрагментов"))
        assertEquals("фрагмента", genitiveAfterOf(21, "фрагмента", "фрагментов"))
        assertEquals("фрагментов", genitiveAfterOf(11, "фрагмента", "фрагментов"))
        assertEquals("фрагментов", genitiveAfterOf(45, "фрагмента", "фрагментов"))
    }

    @Test
    fun labels() {
        assertEquals("1${NBSP}говорящий", speakersLabel(1))
        assertEquals("3${NBSP}говорящих", speakersLabel(3))
        assertEquals("2${NBSP}418${NBSP}реплик", utterancesLabel(2_418))
        assertEquals("3${NBSP}окна", windowsLabel(3))
        assertEquals("1${NBSP}файл", filesLabel(1))
        assertEquals("42 процента", percentSpoken(0.42f))
    }
}
