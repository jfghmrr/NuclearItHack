package ru.stenokot.app.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class LlmDeviceTest {
    @Test
    fun fourVirtualCoresGiveThreeThreads() {
        assertEquals(3, LlmDevice.threadCount(cores = 4, performanceCores = 4))
    }

    @Test
    fun twoVirtualCoresGiveTwoThreads() {
        assertEquals(2, LlmDevice.threadCount(cores = 2, performanceCores = 2))
    }

    @Test
    fun bigLittleIsLimitedByPerformanceCores() {
        assertEquals(2, LlmDevice.threadCount(cores = 8, performanceCores = 2))
        assertEquals(3, LlmDevice.threadCount(cores = 8, performanceCores = 3))
    }

    @Test
    fun neverAboveFourThreads() {
        assertEquals(4, LlmDevice.threadCount(cores = 12, performanceCores = 8))
    }
}
