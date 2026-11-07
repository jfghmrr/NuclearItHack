package ru.stenokot.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.model.LlmStatus

class LlmBudgetPolicyTest {
    @Test
    fun budgetCarriesContextWindowSizeAndStageLimit() {
        val budget = LlmBudgetPolicy.budget()
        assertEquals(1024, budget.nCtx)
        assertEquals(LlmWindows.WINDOW_TOKENS, budget.maxChunkTokens)
        assertEquals(LlmBudgetPolicy.STAGE_LIMIT_MS, budget.deadlineMs)
    }

    @Test
    fun windowRunsToItsOwnLimitUntilStageEnds() {
        val stage = LlmBudgetPolicy.STAGE_LIMIT_MS
        assertEquals(LlmBudgetPolicy.WINDOW_LIMIT_MS, LlmBudgetPolicy.windowLimitMs(0, stage))
        assertEquals(20_000L, LlmBudgetPolicy.windowLimitMs(stage - 20_000, stage))
        assertEquals(0L, LlmBudgetPolicy.windowLimitMs(stage, stage))
        assertEquals(0L, LlmBudgetPolicy.windowLimitMs(stage + 1, stage))
    }

    @Test
    fun watchdogNeverFiresBeforeWindowLimit() {
        assertTrue(LlmBudgetPolicy.WINDOW_WATCHDOG_MS > LlmBudgetPolicy.WINDOW_LIMIT_MS)
    }

    @Test
    fun eightHourRecordingIsBoundedAndEvenlySpread() {
        val minuteWindows = List(480) { i ->
            LlmWindow(i, listOf(utterance(i + 1, 0, i * 60_000L, (i + 1) * 60_000L, "текст")))
        }
        val chosen = LlmWindows.selectEvenly(minuteWindows, LlmBudgetPolicy.MAX_WINDOWS)
        assertEquals(LlmBudgetPolicy.MAX_WINDOWS, chosen.size)
        val gaps = chosen.zipWithNext { a, b -> b.startMs - a.startMs }
        assertTrue(gaps.max() <= 2 * 480 * 60_000L / LlmBudgetPolicy.MAX_WINDOWS)
    }

    @Test
    fun outcomeFollowsWindows() {
        assertEquals(LlmStatus.SKIPPED_BY_BUDGET, LlmBudgetPolicy.outcome(0, 8))
        assertEquals(LlmStatus.PARTIAL, LlmBudgetPolicy.outcome(3, 8))
        assertEquals(LlmStatus.APPLIED, LlmBudgetPolicy.outcome(8, 8))
        assertEquals(LlmStatus.PARTIAL, LlmBudgetPolicy.outcome(LlmBudgetPolicy.MAX_WINDOWS, 480))
    }
}
