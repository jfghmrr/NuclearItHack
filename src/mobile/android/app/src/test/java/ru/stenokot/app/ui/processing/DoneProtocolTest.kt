package ru.stenokot.app.ui.processing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.LlmStatus

class DoneProtocolTest {
    private fun done(status: LlmStatus) = JobState.Done(status, emptyList())

    @Test
    fun protocolIsPromisedOnlyWhenItExists() {
        assertTrue(done(LlmStatus.APPLIED).hasProtocol())
        assertTrue(done(LlmStatus.PARTIAL).hasProtocol())
        LlmStatus.entries
            .filter { it != LlmStatus.APPLIED && it != LlmStatus.PARTIAL }
            .forEach { assertFalse(it.name, done(it).hasProtocol()) }
    }

    @Test
    fun transcriptWithoutFinishedLlmPromisesNoProtocol() {
        assertFalse(JobState.TranscriptReady("x.json").hasProtocol())
    }
}
