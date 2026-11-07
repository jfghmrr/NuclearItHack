package ru.stenokot.app.pipeline

import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.LlmStatus

/** Значения машинной строки `txt_status`. Те же значения идут в logcat с тегом PIPELINE. */
object StatusCodes {
    /** Ни одна задача не влияет на экран. */
    const val IDLE: String = "IDLE"

    /** Код для прерывания без известной причины, например после `kill -9`. */
    const val INTERRUPTED: String = "INTERRUPTED"

    /** Переводит состояние задачи в машинную строку. */
    fun of(state: JobState?): String = when (state) {
        null -> IDLE
        JobState.Queued -> "QUEUED"
        is JobState.Running -> "RUNNING:${state.stage}"
        is JobState.Paused -> "RUNNING:${state.stage}"
        is JobState.TranscriptReady -> "TRANSCRIPT_READY"
        JobState.WaitingModel -> "WAITING_MODEL"
        is JobState.Interrupted -> "FAILED:${state.code?.name ?: INTERRUPTED}"
        is JobState.Failed -> "FAILED:${state.code}"
        JobState.Cancelled -> "CANCELLED"
        is JobState.Done -> if (state.llm == LlmStatus.FAILED) "DONE_LLM_FAILED" else "DONE"
    }
}
