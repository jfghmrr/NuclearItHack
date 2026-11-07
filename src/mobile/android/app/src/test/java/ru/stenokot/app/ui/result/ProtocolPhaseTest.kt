package ru.stenokot.app.ui.result

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.app.domain.ProtocolUi
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.Stage

class ProtocolPhaseTest {
    private fun protocol(
        status: LlmStatus? = null,
        running: Boolean = false,
        done: Int? = null,
        total: Int? = null,
        error: ErrorCode? = null,
    ) = ProtocolUi(status, running, done, total, null, null, false, null, error)

    private fun done(status: LlmStatus) = JobState.Done(status, emptyList())

    @Test
    fun `первые секунды после сохранения`() {
        assertEquals(ProtocolPhase.SOON, protocolPhase(JobState.TranscriptReady("p"), protocol(), false))
    }

    @Test
    fun `счётчик появляется только после первого окна`() {
        val state = JobState.Running(Stage.LLM, 0f)
        assertEquals(ProtocolPhase.LOADING, protocolPhase(state, protocol(running = true), false))
        assertEquals(ProtocolPhase.LOADING, protocolPhase(state, protocol(running = true, done = 0, total = 8), false))
        assertEquals(ProtocolPhase.RUNNING, protocolPhase(state, protocol(running = true, done = 3, total = 8), false))
    }

    @Test
    fun `ожидание модели`() {
        assertEquals(ProtocolPhase.WAITING_MODEL, protocolPhase(JobState.WaitingModel, protocol(), false))
    }

    @Test
    fun `итоги модели`() {
        val cases = mapOf(
            LlmStatus.APPLIED to ProtocolPhase.APPLIED,
            LlmStatus.PARTIAL to ProtocolPhase.PARTIAL,
            LlmStatus.DEFERRED to ProtocolPhase.DEFERRED,
            LlmStatus.CANCELLED_BY_NEW_JOB to ProtocolPhase.CANCELLED_BY_NEW_JOB,
            LlmStatus.SKIPPED_BY_BUDGET to ProtocolPhase.SKIPPED_BUDGET,
            LlmStatus.SKIPPED_LOW_MEMORY to ProtocolPhase.SKIPPED_MEMORY,
            LlmStatus.FAILED to ProtocolPhase.FAILED,
        )
        cases.forEach { (status, phase) ->
            assertEquals(phase, protocolPhase(done(status), protocol(status), false))
        }
    }

    @Test
    fun `отсутствие модели отличается от сбоя`() {
        val phase = protocolPhase(
            done(LlmStatus.FAILED),
            protocol(LlmStatus.FAILED, error = ErrorCode.LLM_MODEL_UNAVAILABLE),
            false,
        )
        assertEquals(ProtocolPhase.NO_MODEL, phase)
    }

    @Test
    fun `нет речи важнее статуса модели`() {
        assertEquals(
            ProtocolPhase.NO_SPEECH,
            protocolPhase(done(LlmStatus.SKIPPED_BY_BUDGET), protocol(LlmStatus.SKIPPED_BY_BUDGET), true),
        )
    }

    @Test
    fun `документ есть только у готового и частичного протокола`() {
        assertTrue(ProtocolPhase.APPLIED.hasDocument)
        assertTrue(ProtocolPhase.PARTIAL.hasDocument)
        assertFalse(ProtocolPhase.FAILED.hasDocument)
        assertTrue(ProtocolPhase.RUNNING.inProgress)
        assertFalse(ProtocolPhase.APPLIED.inProgress)
    }

    @Test
    fun `интервалы сливаются при наложении и остаются раздельными при разрыве`() {
        val merged = coveredRanges(
            listOf(CoveredRange(380, 570), CoveredRange(0, 100), CoveredRange(90, 190)),
        )
        assertEquals(listOf(CoveredRange(0, 190), CoveredRange(380, 570)), merged)
    }
}
