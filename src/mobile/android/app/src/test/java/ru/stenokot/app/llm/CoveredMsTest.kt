package ru.stenokot.app.llm

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.stenokot.core.protocol.ProtocolSection

class CoveredMsTest {
    private fun section(start: Long, end: Long) = ProtocolSection("тема", start, end, start.toInt(), emptyList())

    @Test
    fun overlappingWindowsAreCountedOnce() {
        assertEquals(1500L, coveredMs(listOf(section(0, 1000), section(800, 1500))))
    }

    @Test
    fun gapIsNotCovered() {
        assertEquals(1500L, coveredMs(listOf(section(0, 1000), section(2000, 2500))))
    }

    @Test
    fun nestedSectionAddsNothing() {
        assertEquals(1000L, coveredMs(listOf(section(0, 1000), section(200, 300))))
    }

    @Test
    fun orderDoesNotMatter() {
        assertEquals(1500L, coveredMs(listOf(section(800, 1500), section(0, 1000))))
    }

    @Test
    fun emptyProtocolCoversNothing() {
        assertEquals(0L, coveredMs(emptyList()))
    }
}
