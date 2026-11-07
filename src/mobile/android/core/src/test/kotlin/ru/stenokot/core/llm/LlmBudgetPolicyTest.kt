package ru.stenokot.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.model.LlmStatus

class LlmBudgetPolicyTest {
    @Test
    fun formulaBranches() {
        assertEquals(15_000L, LlmBudgetPolicy.budgetMs(30_000, 18_000))
        assertEquals(15_000L, LlmBudgetPolicy.budgetMs(10_000, 0))
        assertEquals(60_000L, LlmBudgetPolicy.budgetMs(600_000, 100_000))
        assertEquals(90_000L, LlmBudgetPolicy.budgetMs(3_600_000, 0))
        assertEquals(40_000L, LlmBudgetPolicy.budgetMs(600_000, 530_000))
        assertEquals(15_000L, LlmBudgetPolicy.budgetMs(600_000, 560_000))
        assertEquals(15_000L, LlmBudgetPolicy.budgetMs(600_000, 2_000_000))
    }

    @Test
    fun shortRecordingStillGetsAttemptAndExceedsTargetHonestly() {
        val budget = LlmBudgetPolicy.budgetMs(30_000, 18_000)
        assertTrue((18_000 + budget) / 30_000.0 > 0.95)
        assertTrue(LlmBudgetPolicy.mayStartWindow(0, 100_000, budget, 40_000))
        assertEquals(45_000L, LlmBudgetPolicy.windowLimitMs(0, 100_000, budget))
    }

    @Test
    fun laterWindowsMustFitBudget() {
        assertTrue(LlmBudgetPolicy.mayStartWindow(1, 40_000, 60_000, 20_000))
        assertFalse(LlmBudgetPolicy.mayStartWindow(1, 40_001, 60_000, 20_000))
        assertEquals(20_000L, LlmBudgetPolicy.windowLimitMs(1, 40_000, 60_000))
        assertEquals(0L, LlmBudgetPolicy.windowLimitMs(1, 70_000, 60_000))
    }

    @Test
    fun budgetCarriesContextAndWindowSize() {
        val budget = LlmBudgetPolicy.budget(3_600_000, 0)
        assertEquals(1024, budget.nCtx)
        assertEquals(300, budget.maxChunkTokens)
        assertEquals(90_000L, budget.deadlineMs)
    }

    /** Значения совпадают с выводом опорного расчёта на тех же профилях. */
    @Test
    fun planMatchesReferenceProfiles() {
        data class Row(val p: Double, val d: Double, val t: Long, val fit: Int, val total: Int, val used: Double, val coverage: Int)
        val rows = listOf(
            Row(300.0, 35.0, 600, 8, 8, 25.4, 103),
            Row(300.0, 35.0, 3600, 51, 70, 89.0, 73),
            Row(100.0, 13.0, 600, 8, 8, 58.8, 103),
            Row(100.0, 13.0, 3600, 17, 70, 87.7, 24),
            Row(55.0, 8.0, 600, 4, 8, 55.3, 51),
            Row(55.0, 8.0, 3600, 9, 70, 84.1, 13),
            Row(25.0, 3.5, 600, 1, 8, 41.7, 13),
            Row(25.0, 3.5, 3600, 4, 70, 88.9, 6),
        )
        for (row in rows) {
            val plan = LlmBudgetPolicy.plan(row.t * 1000, LlmSpeed(row.p, row.d))
            assertEquals(row.fit, plan.windowsFit)
            assertEquals(row.total, plan.windowsTotal)
            assertEquals(row.used, plan.usedMs / 1000.0, 0.05)
            assertEquals(row.coverage.toDouble(), plan.coverage * 100, 0.5)
        }
    }

    @Test
    fun nextWindowShrinksToRemainder() {
        val speed = LlmSpeed(100.0, 10.0)
        assertEquals(300, LlmBudgetPolicy.nextWindowTokens(60_000, speed, 300))
        assertEquals(150, LlmBudgetPolicy.nextWindowTokens(3_500, speed, 300))
        assertNull(LlmBudgetPolicy.nextWindowTokens(2_900, speed, 300))
    }

    @Test
    fun outcomeFollowsWindows() {
        assertEquals(LlmStatus.SKIPPED_BY_BUDGET, LlmBudgetPolicy.outcome(0, 8))
        assertEquals(LlmStatus.PARTIAL, LlmBudgetPolicy.outcome(3, 8))
        assertEquals(LlmStatus.APPLIED, LlmBudgetPolicy.outcome(8, 8))
    }
}
