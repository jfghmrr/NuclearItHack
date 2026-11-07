package ru.stenokot.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusCodeTest {
    @Test
    fun allStatesMapToExactValues() {
        assertEquals("IDLE", statusCode(null))
        assertEquals("QUEUED", statusCode(JobState.Queued))
        assertEquals("RUNNING:ASR", statusCode(JobState.Running(Stage.ASR, 0.4f)))
        assertEquals("RUNNING:LANG_ID", statusCode(JobState.Running(Stage.LANG_ID, 0f)))
        assertEquals("RUNNING:LLM", statusCode(JobState.Running(Stage.LLM, 0.1f)))
        assertEquals("TRANSCRIPT_READY", statusCode(JobState.TranscriptReady("/x")))
        assertEquals("WAITING_MODEL", statusCode(JobState.WaitingModel))
        assertEquals("CANCELLED", statusCode(JobState.Cancelled))
    }

    @Test
    fun thermalPauseStaysRunning() {
        assertEquals("RUNNING:DIARIZE", statusCode(JobState.Paused(Stage.DIARIZE, PauseReason.THERMAL)))
        assertEquals("QUEUED", statusCode(JobState.Paused(Stage.IMPORT, PauseReason.QUEUED_BEHIND_OTHER_JOB)))
    }

    @Test
    fun interruptedIsFailedWithCode() {
        assertEquals(
            "FAILED:FGS_TIMEOUT",
            statusCode(JobState.Interrupted(Stage.ASR, ErrorCode.FGS_TIMEOUT)),
        )
        assertEquals("FAILED:INTERRUPTED", statusCode(JobState.Interrupted(Stage.ASR, null)))
    }

    @Test
    fun failureUsesCodeAndNoSpeechIsDone() {
        assertEquals(
            "FAILED:DECODE_CORRUPT",
            statusCode(JobState.Failed(Stage.DECODE, ErrorCode.DECODE_CORRUPT, null)),
        )
        assertEquals("DONE", statusCode(JobState.Failed(Stage.VAD, ErrorCode.AUDIO_EMPTY, null)))
    }

    @Test
    fun onlyFailedLlmChangesDone() {
        LlmStatus.entries.forEach { status ->
            val expected = if (status == LlmStatus.FAILED) "DONE_LLM_FAILED" else "DONE"
            assertEquals(expected, statusCode(JobState.Done(status, emptyList())))
        }
    }
}
