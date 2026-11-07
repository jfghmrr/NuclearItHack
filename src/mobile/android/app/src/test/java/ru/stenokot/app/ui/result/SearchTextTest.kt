package ru.stenokot.app.ui.result

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTextTest {
    @Test
    fun `свёртка сохраняет длину и не различает ё`() {
        val source = "Ёлка и ЕЛКА"
        val folded = foldForSearch(source)
        assertEquals(source.length, folded.length)
        assertEquals("елка и елка", folded)
    }

    @Test
    fun `символ с особым нижним регистром не сдвигает позиции`() {
        val source = "İstanbul решено"
        val folded = foldForSearch(source)
        assertEquals(source.length, folded.length)
        val match = findMatches(folded, "решено")
        assertEquals("решено", source.substring(match[0], match[1]))
    }

    @Test
    fun `находит все вхождения без пересечений`() {
        assertArrayEquals(intArrayOf(0, 2, 2, 4), findMatches("аааа", "аа"))
    }

    @Test
    fun `пустой запрос и отсутствие вхождений дают пустой результат`() {
        assertEquals(0, findMatches("текст", "").size)
        assertEquals(0, findMatches("текст", "кот").size)
    }

    @Test
    fun `запрос очищается от крайних пробелов и регистра`() {
        assertEquals("полив", normalizeQuery("  ПолИв "))
    }
}
