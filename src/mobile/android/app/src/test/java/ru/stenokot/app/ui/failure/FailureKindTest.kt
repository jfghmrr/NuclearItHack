package ru.stenokot.app.ui.failure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.stenokot.app.ui.components.MascotKind
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.PreflightInfo
import ru.stenokot.core.model.Stage

class FailureKindTest {
    private fun failed(code: ErrorCode, stage: Stage = Stage.ASR) = JobState.Failed(stage, code, null)

    private fun preflight(free: Long, required: Long) = PreflightInfo(
        fileName = "a.mp3",
        sizeBytes = 1,
        durationMs = 1,
        mime = null,
        freeBytes = free,
        requiredBytes = required,
        estimateMinMs = null,
        estimateMaxMs = null,
    )

    @Test
    fun everyStateOfTheBriefHasItsKind() {
        assertEquals(FailureKind.MODEL, failureKind(failed(ErrorCode.MODEL_LOAD_FAILED)))
        assertEquals(FailureKind.FORMAT, failureKind(failed(ErrorCode.DECODE_UNSUPPORTED_FORMAT)))
        assertEquals(FailureKind.CORRUPT, failureKind(failed(ErrorCode.DECODE_CORRUPT)))
        assertEquals(FailureKind.READ, failureKind(failed(ErrorCode.IMPORT_READ_FAILED)))
        assertEquals(FailureKind.NO_PERMISSION, failureKind(failed(ErrorCode.IMPORT_NO_PERMISSION)))
        assertEquals(FailureKind.STORAGE, failureKind(failed(ErrorCode.STORAGE_LOW)))
        assertEquals(FailureKind.MEMORY, failureKind(JobState.Interrupted(Stage.ASR, ErrorCode.LOW_MEMORY)))
        assertEquals(FailureKind.PROCESSING, failureKind(failed(ErrorCode.CONTRACT_INVALID)))
        assertEquals(FailureKind.INTERRUPTED, failureKind(JobState.Interrupted(Stage.DIARIZE, ErrorCode.FGS_TIMEOUT)))
    }

    @Test
    fun codesWithoutOwnTextBecomeProcessingError() {
        assertEquals(FailureKind.PROCESSING, failureKind(failed(ErrorCode.LANG_ID_FAILED)))
        assertEquals(FailureKind.PROCESSING, failureKind(failed(ErrorCode.THERMAL_CRITICAL)))
    }

    @Test
    fun interruptedJobIsNeverAnError() {
        assertEquals(FailureKind.INTERRUPTED, failureKind(JobState.Interrupted(Stage.ASR, null)))
        assertEquals(FailureKind.INTERRUPTED, failureKind(JobState.Interrupted(Stage.ASR, ErrorCode.CONTRACT_INVALID)))
    }

    @Test
    fun workingStatesAreNotFailures() {
        assertNull(failureKind(JobState.Queued))
        assertNull(failureKind(JobState.Running(Stage.ASR, 0.5f)))
        assertNull(failureKind(JobState.Done(LlmStatus.APPLIED, emptyList())))
    }

    @Test
    fun catIsAbsentOnlyWhereTheBriefSaysSo() {
        assertNull(FailureKind.MODEL.mascot)
        assertNull(FailureKind.PROCESSING.mascot)
        assertEquals(MascotKind.FILE_ERROR, FailureKind.FORMAT.mascot)
        assertEquals(MascotKind.FILE_ERROR, FailureKind.CORRUPT.mascot)
        assertEquals(MascotKind.FILE_ERROR, FailureKind.READ.mascot)
        assertEquals(MascotKind.STORAGE, FailureKind.STORAGE.mascot)
        assertEquals(MascotKind.MEMORY, FailureKind.MEMORY.mascot)
        assertEquals(MascotKind.INTERRUPTED, FailureKind.INTERRUPTED.mascot)
    }

    @Test
    fun primaryActionFixesTheCause() {
        assertEquals(FailureAction.MODELS, FailureKind.MODEL.actions.primary)
        assertEquals(FailureAction.IMPORT, FailureKind.CORRUPT.actions.primary)
        assertNull(FailureKind.CORRUPT.actions.secondary)
        assertEquals(FailureAction.RETRY, FailureKind.STORAGE.actions.primary)
        assertEquals(FailureAction.RETRY, FailureKind.MEMORY.actions.primary)
        assertEquals(FailureAction.IMPORT, FailureKind.MEMORY.actions.secondary)
        assertEquals(FailureAction.RETRY, FailureKind.INTERRUPTED.actions.primary)
    }

    @Test
    fun stageComesFromTheStoppedJob() {
        assertEquals(Stage.DIARIZE, failureStage(JobState.Interrupted(Stage.DIARIZE, null)))
        assertEquals(Stage.DECODE, failureStage(failed(ErrorCode.DECODE_CORRUPT, Stage.DECODE)))
        assertNull(failureStage(JobState.Queued))
    }

    @Test
    fun spaceToFreeIsKnownOnlyFromPreflight() {
        assertEquals(700L, bytesToFree(preflight(free = 300, required = 1_000)))
        assertNull(bytesToFree(preflight(free = 2_000, required = 1_000)))
        assertNull(bytesToFree(null))
    }
}
