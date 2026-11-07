package ru.stenokot.app.ui.result

import ru.stenokot.app.domain.ProtocolUi
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.LlmStatus

/** Положение протокола. Стенограмма от него не зависит: это отдельный слой поверх неё. */
enum class ProtocolPhase {
    SOON,
    LOADING,
    RUNNING,
    WAITING_MODEL,
    APPLIED,
    PARTIAL,
    DEFERRED,
    CANCELLED_BY_NEW_JOB,
    SKIPPED_MEMORY,
    SKIPPED_BUDGET,
    FAILED,
    NO_MODEL,
    NO_SPEECH,
}

/** Протокол можно открыть: документ уже есть. */
val ProtocolPhase.hasDocument: Boolean
    get() = this == ProtocolPhase.APPLIED || this == ProtocolPhase.PARTIAL

/** Протокол ещё будет: идёт работа или её ждут. */
val ProtocolPhase.inProgress: Boolean
    get() = this == ProtocolPhase.SOON ||
        this == ProtocolPhase.LOADING ||
        this == ProtocolPhase.RUNNING ||
        this == ProtocolPhase.WAITING_MODEL

private fun failedPhase(protocol: ProtocolUi): ProtocolPhase =
    if (protocol.errorCode == ErrorCode.LLM_MODEL_UNAVAILABLE) ProtocolPhase.NO_MODEL else ProtocolPhase.FAILED

private fun finishedPhase(status: LlmStatus, protocol: ProtocolUi): ProtocolPhase = when (status) {
    LlmStatus.APPLIED -> ProtocolPhase.APPLIED
    LlmStatus.PARTIAL -> ProtocolPhase.PARTIAL
    LlmStatus.DEFERRED -> ProtocolPhase.DEFERRED
    LlmStatus.CANCELLED_BY_NEW_JOB -> ProtocolPhase.CANCELLED_BY_NEW_JOB
    LlmStatus.SKIPPED_BY_BUDGET -> ProtocolPhase.SKIPPED_BUDGET
    LlmStatus.SKIPPED_LOW_MEMORY -> ProtocolPhase.SKIPPED_MEMORY
    LlmStatus.FAILED -> failedPhase(protocol)
}

/** Счётчик окон показывается, только когда первое окно уже посчитано. */
private fun runningPhase(protocol: ProtocolUi): ProtocolPhase =
    if ((protocol.windowsDone ?: 0) > 0 && protocol.windowsTotal != null) {
        ProtocolPhase.RUNNING
    } else {
        ProtocolPhase.LOADING
    }

/**
 * Определяет положение протокола по состоянию задачи. Пустая стенограмма важнее статуса модели:
 * без речи протокола нет и быть не может.
 */
fun protocolPhase(state: JobState, protocol: ProtocolUi, noSpeech: Boolean): ProtocolPhase = when {
    noSpeech -> ProtocolPhase.NO_SPEECH
    state is JobState.Done -> finishedPhase(state.llm, protocol)
    state == JobState.WaitingModel -> ProtocolPhase.WAITING_MODEL
    state is JobState.TranscriptReady -> ProtocolPhase.SOON
    protocol.running -> runningPhase(protocol)
    protocol.status != null -> finishedPhase(protocol.status, protocol)
    else -> ProtocolPhase.LOADING
}

/** Интервал времени. */
data class CoveredRange(val startMs: Long, val endMs: Long)

/** Обработанные интервалы: разделы идут по времени, соседние сливаются в один. */
fun coveredRanges(ranges: List<CoveredRange>): List<CoveredRange> {
    val merged = ArrayList<CoveredRange>(ranges.size)
    for (range in ranges.sortedBy { it.startMs }) {
        val last = merged.lastOrNull()
        if (last != null && range.startMs <= last.endMs) {
            merged[merged.lastIndex] = CoveredRange(last.startMs, maxOf(last.endMs, range.endMs))
        } else {
            merged += range
        }
    }
    return merged
}
