package ru.stenokot.app.ui.format

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.LlmStatus
import ru.stenokot.core.model.PauseReason
import ru.stenokot.core.model.Stage

class StatusCodesTest {
    @Test
    fun codesMatchAutomationContract() {
        assertEquals("QUEUED", statusCode(JobState.Queued))
        assertEquals("RUNNING:ASR", statusCode(JobState.Running(Stage.ASR, 0.4f)))
        assertEquals("RUNNING:ASR", statusCode(JobState.Paused(Stage.ASR, PauseReason.THERMAL)))
        assertEquals("TRANSCRIPT_READY", statusCode(JobState.TranscriptReady("x")))
        assertEquals("WAITING_MODEL", statusCode(JobState.WaitingModel))
        assertEquals("FAILED:FGS_TIMEOUT", statusCode(JobState.Interrupted(Stage.ASR, ErrorCode.FGS_TIMEOUT)))
        assertEquals("FAILED:DECODE_CORRUPT", statusCode(JobState.Failed(Stage.DECODE, ErrorCode.DECODE_CORRUPT, null)))
        assertEquals("CANCELLED", statusCode(JobState.Cancelled))
        assertEquals("DONE", statusCode(JobState.Done(LlmStatus.PARTIAL, emptyList())))
        assertEquals("DONE_LLM_FAILED", statusCode(JobState.Done(LlmStatus.FAILED, emptyList())))
    }

    @Test
    fun spokenStatusIsRussian() {
        assertEquals("Состояние: ожидание файла", statusSpoken("IDLE"))
        assertEquals("Состояние: идёт этап Распознавание речи", statusSpoken("RUNNING:ASR"))
        assertEquals("Состояние: ошибка", statusSpoken("FAILED:DECODE_CORRUPT"))
    }

    @Test
    fun queueBadgesCoverProductStatuses() {
        assertEquals("Ожидает", jobBadge(JobState.Queued).label)
        assertEquals("Обрабатывается", jobBadge(JobState.Running(Stage.ASR, 0f)).label)
        assertEquals("Приостановлено", jobBadge(JobState.Paused(Stage.ASR, PauseReason.THERMAL)).label)
        assertEquals("Приостановлено", jobBadge(JobState.Interrupted(Stage.ASR, null)).label)
        assertEquals("Ошибка", jobBadge(JobState.Failed(Stage.ASR, ErrorCode.CONTRACT_INVALID, null)).label)
        assertEquals("Результат сохранён", jobBadge(JobState.TranscriptReady("x")).label)
        assertEquals("Результат сохранён", jobBadge(JobState.Running(Stage.LLM, 0.3f)).label)
        assertEquals("Готово", jobBadge(JobState.Done(LlmStatus.APPLIED, emptyList())).label)
    }

    @Test
    fun protocolStatusLine() {
        assertEquals("Протокол формируется", protocolLabel(JobState.Running(Stage.LLM, 0.1f)))
        assertEquals("Протокол не создан", protocolLabel(JobState.Done(LlmStatus.FAILED, emptyList())))
        assertEquals("Речи не найдено", protocolLabel(JobState.Done(LlmStatus.DEFERRED, listOf("AUDIO_EMPTY"))))
    }

    @Test
    fun stageCounters() {
        assertEquals("19 из 45${NBSP}фрагментов", formatStageCounter(Stage.ASR, 19, 45))
        assertEquals("1 из 3${NBSP}окон", formatStageCounter(Stage.DIARIZE, 1, 3))
        assertEquals("08:35 из 12:48", formatStageCounter(Stage.DECODE, 515, 768))
        assertEquals("7,9 из 11,7${NBSP}МиБ", formatStageCounter(Stage.IMPORT, 8_090, 11_981))
    }
}
