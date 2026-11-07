package ru.stenokot.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.stenokot.app.domain.JobSummaryUi
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.Stage

class HomeSectionsTest {
    private fun job(id: String, state: JobState, createdAt: Long = 0, position: Int? = null) =
        JobSummaryUi(id, id, createdAt, null, null, state, position)

    @Test
    fun queueKeepsFifoOrderBehindRunningJob() {
        val sections = homeSections(
            listOf(
                job("b", JobState.Queued, position = 2),
                job("run", JobState.Running(Stage.ASR, 0.1f)),
                job("a", JobState.Queued, position = 1),
            ),
        )
        assertEquals("run", sections.active?.id)
        assertNull(sections.selected)
        assertEquals(listOf("a", "b"), sections.queue.map { it.id })
        assertEquals(2, sections.queuedCount)
    }

    @Test
    fun firstQueuedBecomesSelectedWhenNothingRuns() {
        val sections = homeSections(
            listOf(job("b", JobState.Queued, position = 2), job("a", JobState.Queued, position = 1)),
        )
        assertEquals("a", sections.selected?.id)
        assertEquals(listOf("b"), sections.queue.map { it.id })
    }

    @Test
    fun protocolStageDoesNotBlockTheQueueCard() {
        val sections = homeSections(listOf(job("llm", JobState.Running(Stage.LLM, 0.1f))))
        assertNull(sections.active)
        assertEquals(listOf("llm"), sections.history.map { it.id })
    }

    @Test
    fun interruptedIsSeparateFromHistory() {
        val sections = homeSections(
            listOf(
                job("old", JobState.Done(LlmStatus.APPLIED, emptyList()), createdAt = 1),
                job("new", JobState.Done(LlmStatus.APPLIED, emptyList()), createdAt = 2),
                job("cut", JobState.Interrupted(Stage.ASR, ErrorCode.FGS_TIMEOUT)),
            ),
        )
        assertEquals("cut", sections.interrupted?.id)
        assertEquals(listOf("new", "old"), sections.history.map { it.id })
    }
}
