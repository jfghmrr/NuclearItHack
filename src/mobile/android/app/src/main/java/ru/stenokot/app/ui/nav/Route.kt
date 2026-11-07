package ru.stenokot.app.ui.nav

import androidx.compose.runtime.Immutable
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.Stage

/** Вкладка экрана результата. */
enum class ResultTab { TRANSCRIPT, PROTOCOL }

/** Экран приложения. Параметры являются строками и числами, чтобы маршрут переживал поворот. */
@Immutable
sealed interface Route {
    /** SCR-01. */
    data object Home : Route

    /** SCR-03. */
    data class Processing(val jobId: String) : Route

    /** SCR-04 и SCR-05. [focusUtterance] это реплика, к которой надо перейти при открытии. */
    data class Result(
        val jobId: String,
        val tab: ResultTab = ResultTab.TRANSCRIPT,
        val focusUtterance: Int? = null,
    ) : Route

    /** SCR-07. */
    data class Failure(val jobId: String) : Route

    /** SCR-08. */
    data object Verification : Route

    /** Шрифты, модели и библиотеки с их лицензиями. */
    data object Licenses : Route

    /** Текст лицензии записи [entryId] из перечня. */
    data class LicenseText(val entryId: String) : Route
}

/** Экран, на котором должна быть видна задача в состоянии [state]. */
fun routeForJob(jobId: String, state: JobState): Route = when (state) {
    JobState.Queued,
    JobState.Cancelled,
    is JobState.Paused,
    -> Route.Processing(jobId)
    is JobState.Running ->
        if (state.stage == Stage.LLM) Route.Result(jobId) else Route.Processing(jobId)
    is JobState.TranscriptReady,
    JobState.WaitingModel,
    is JobState.Done,
    -> Route.Result(jobId)
    is JobState.Failed,
    is JobState.Interrupted,
    -> Route.Failure(jobId)
}

/** Ключ сохраняемого состояния экрана: один на задачу, вкладка и переход к реплике в него не входят. */
internal val Route.stateKey: String
    get() = when (this) {
        is Route.Result -> "result|$jobId"
        else -> encode()
    }

/** Запись маршрута в строку для сохранения между пересозданиями активности. */
internal fun Route.encode(): String = when (this) {
    Route.Home -> "home"
    Route.Verification -> "verification"
    Route.Licenses -> "licenses"
    is Route.LicenseText -> "license|$entryId"
    is Route.Processing -> "processing|$jobId"
    is Route.Failure -> "failure|$jobId"
    is Route.Result -> "result|$jobId|${tab.name}|${focusUtterance ?: ""}"
}

/** Обратно к маршруту. Неизвестная запись ведёт на Главную. */
internal fun decodeRoute(value: String): Route {
    val parts = value.split('|')
    return when (parts[0]) {
        "verification" -> Route.Verification
        "licenses" -> Route.Licenses
        "license" -> Route.LicenseText(parts[1])
        "processing" -> Route.Processing(parts[1])
        "failure" -> Route.Failure(parts[1])
        "result" -> Route.Result(parts[1], ResultTab.valueOf(parts[2]), parts[3].toIntOrNull())
        else -> Route.Home
    }
}
