package ru.stenokot.core.model

/**
 * Машинная строка `txt_status`, те же значения идут в журнал с тегом PIPELINE.
 * Значение null означает, что задачи нет. Перегрев не меняет значение стадии: работа продолжится
 * сама, и автоматика не должна считать это сбоем. Задача, ждущая другую, показывается как QUEUED.
 * Отсутствие речи завершает задачу пустым контрактным файлом, поэтому это DONE, а не сбой.
 */
fun statusCode(state: JobState?): String =
    when (state) {
        null -> "IDLE"
        JobState.Queued -> "QUEUED"
        is JobState.Running -> "RUNNING:${state.stage.name}"
        is JobState.TranscriptReady -> "TRANSCRIPT_READY"
        JobState.WaitingModel -> "WAITING_MODEL"
        is JobState.Paused -> when (state.reason) {
            PauseReason.THERMAL -> "RUNNING:${state.stage.name}"
            PauseReason.QUEUED_BEHIND_OTHER_JOB -> "QUEUED"
        }
        is JobState.Interrupted -> "FAILED:${state.code?.name ?: INTERRUPTED_CODE}"
        is JobState.Failed -> when (state.code) {
            ErrorCode.AUDIO_EMPTY -> "DONE"
            else -> "FAILED:${state.code.name}"
        }
        JobState.Cancelled -> "CANCELLED"
        is JobState.Done -> when (state.llm) {
            LlmStatus.FAILED -> "DONE_LLM_FAILED"
            else -> "DONE"
        }
    }

/** Код для прерывания без причины, например после убийства процесса системой. */
private const val INTERRUPTED_CODE = "INTERRUPTED"
