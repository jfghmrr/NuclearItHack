package ru.stenokot.app.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.Stage

class DeleteDialogTest {
    @Test
    fun jsonExistsFromTheMomentItIsPublished() {
        assertTrue(hasExportedJson(JobState.TranscriptReady("a.json")))
        assertTrue(hasExportedJson(JobState.WaitingModel))
        assertTrue(hasExportedJson(JobState.Running(Stage.LLM, 0.1f)))
        assertTrue(hasExportedJson(JobState.Done(LlmStatus.FAILED, emptyList())))
    }

    @Test
    fun noJsonBeforeValidation() {
        assertFalse(hasExportedJson(JobState.Queued))
        assertFalse(hasExportedJson(JobState.Running(Stage.ASR, 0.1f)))
        assertFalse(hasExportedJson(JobState.Failed(Stage.VALIDATE, ErrorCode.CONTRACT_INVALID, null)))
        assertFalse(hasExportedJson(JobState.Interrupted(Stage.ASR, null)))
        assertFalse(hasExportedJson(JobState.Cancelled))
    }
}
