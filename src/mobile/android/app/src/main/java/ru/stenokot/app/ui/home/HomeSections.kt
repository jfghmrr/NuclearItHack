package ru.stenokot.app.ui.home

import androidx.compose.runtime.Immutable
import ru.stenokot.app.domain.JobSummaryUi
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.PauseReason
import ru.stenokot.core.model.Stage

/** Задачи главного экрана, разложенные по смыслу. */
@Immutable
data class HomeSections(
    val active: JobSummaryUi?,
    val selected: JobSummaryUi?,
    val queue: List<JobSummaryUi>,
    val interrupted: JobSummaryUi?,
    val history: List<JobSummaryUi>,
) {
    /** Сколько файлов ждёт очереди, включая выбранный. */
    val queuedCount: Int get() = queue.size + if (selected != null) 1 else 0
}

private fun JobState.isActive(): Boolean = when (this) {
    is JobState.Running -> stage != Stage.LLM
    is JobState.Paused -> reason != PauseReason.QUEUED_BEHIND_OTHER_JOB
    else -> false
}

private fun JobState.isWaiting(): Boolean =
    this == JobState.Queued || (this is JobState.Paused && reason == PauseReason.QUEUED_BEHIND_OTHER_JOB)

/**
 * Раскладывает задачи: идущая обработка, очередь в порядке постановки, прерванная и история.
 * Пока обработки нет, первый файл очереди считается выбранным: он вот-вот запустится.
 */
fun homeSections(jobs: List<JobSummaryUi>): HomeSections {
    val active = jobs.firstOrNull { it.state.isActive() }
    val waiting = jobs.filter { it.state.isWaiting() }.sortedBy { it.queuePosition ?: Int.MAX_VALUE }
    val selected = if (active == null) waiting.firstOrNull() else null
    val interrupted = jobs.firstOrNull { it.state is JobState.Interrupted }
    val history = jobs
        .filter { !it.state.isActive() && !it.state.isWaiting() && it != interrupted }
        .sortedByDescending { it.createdAtMs }
    return HomeSections(
        active = active,
        selected = selected,
        queue = if (selected != null) waiting.drop(1) else waiting,
        interrupted = interrupted,
        history = history,
    )
}
