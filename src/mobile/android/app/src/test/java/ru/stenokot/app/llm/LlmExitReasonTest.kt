package ru.stenokot.app.llm

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.stenokot.core.model.ErrorCode

class LlmExitReasonTest {
    @Test
    fun lowMemoryReportIsOom() {
        assertEquals(ErrorCode.LLM_DIED_OOM, LlmExitReason.classify(ApplicationExitInfo.REASON_LOW_MEMORY, 0))
    }

    @Test
    fun nativeCrashIsNative() {
        assertEquals(ErrorCode.LLM_DIED_NATIVE, LlmExitReason.classify(ApplicationExitInfo.REASON_CRASH_NATIVE, 11))
    }

    @Test
    fun javaCrashIsNotOom() {
        assertEquals(ErrorCode.LLM_DIED_NATIVE, LlmExitReason.classify(ApplicationExitInfo.REASON_CRASH, 1))
    }

    @Test
    fun unexplainedSigkillIsTreatedAsOom() {
        assertEquals(ErrorCode.LLM_DIED_OOM, LlmExitReason.classify(ApplicationExitInfo.REASON_SIGNALED, 9))
    }

    @Test
    fun otherSignalIsNative() {
        assertEquals(ErrorCode.LLM_DIED_NATIVE, LlmExitReason.classify(ApplicationExitInfo.REASON_SIGNALED, 11))
    }

    @Test
    fun unknownReasonHasNoCode() {
        assertNull(LlmExitReason.classify(ApplicationExitInfo.REASON_OTHER, 0))
        assertNull(LlmExitReason.classify(ApplicationExitInfo.REASON_EXIT_SELF, 0))
    }
}
