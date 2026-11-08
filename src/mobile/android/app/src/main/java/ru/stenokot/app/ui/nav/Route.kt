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

    /** Запись с микрофона. */
    data object Record : Route

    /** Объяснение перед системным запросом микрофона. */
    data object MicPermission : Route

    /** Настройки приложения. */
    data object Settings : Route

    /** Помощь: как пользоваться и частые вопросы. */
    data object Help : Route

    /** SCR-03. */
    data class Processing(val jobId: String) : Route

    /**
     * SCR-04 и SCR-05. [focusUtterance] это реплика, к которой надо перейти при открытии, [play]
     * сразу запускает её звук.
     */
    data class Result(
        val jobId: String,
        val tab: ResultTab = ResultTab.TRANSCRIPT,
        val focusUtterance: Int? = null,
        val play: Boolean = false,
    ) : Route

    /** Сведения о файле результата: размер, путь, контрольная сумма. */
    data class ResultFile(val jobId: String) : Route

    /** Пункт протокола с цитатой. [index] считается по всем пунктам в порядке времени. */
    data class ProtocolItem(val jobId: String, val index: Int) : Route

    /** SCR-07. */
    data class Failure(val jobId: String) : Route

    /** Модели на устройстве. */
    data object Models : Route

    /** Сведения о модели [name]. */
    data class ModelCard(val name: String) : Route

    /** Что и где происходит с разговором, с проверкой пакета на месте. */
    data object Privacy : Route

    /** Разрешения приложения: назначение и технический список. */
    data object Permissions : Route

    /** SCR-08: замеры последней записи. */
    data object Diagnostics : Route

    /** Версия приложения и переходы к правовым сведениям. */
    data object About : Route

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
    Route.Record -> "record"
    Route.MicPermission -> "mic"
    Route.Settings -> "settings"
    Route.Help -> "help"
    Route.Models -> "models"
    is Route.ModelCard -> "model|$name"
    Route.Privacy -> "privacy"
    Route.Permissions -> "permissions"
    Route.Diagnostics -> "diagnostics"
    Route.About -> "about"
    Route.Licenses -> "licenses"
    is Route.LicenseText -> "license|$entryId"
    is Route.Processing -> "processing|$jobId"
    is Route.Failure -> "failure|$jobId"
    is Route.Result -> "result|$jobId|${tab.name}|${focusUtterance ?: ""}|$play"
    is Route.ResultFile -> "file|$jobId"
    is Route.ProtocolItem -> "item|$jobId|$index"
}

/** Обратно к маршруту. Неизвестная запись ведёт на Главную. */
internal fun decodeRoute(value: String): Route {
    val parts = value.split('|')
    return when (parts[0]) {
        "record" -> Route.Record
        "mic" -> Route.MicPermission
        "settings" -> Route.Settings
        "help" -> Route.Help
        "models" -> Route.Models
        "model" -> Route.ModelCard(parts[1])
        "privacy" -> Route.Privacy
        "permissions" -> Route.Permissions
        "diagnostics" -> Route.Diagnostics
        "about" -> Route.About
        "licenses" -> Route.Licenses
        "license" -> Route.LicenseText(parts[1])
        "processing" -> Route.Processing(parts[1])
        "failure" -> Route.Failure(parts[1])
        "result" -> Route.Result(parts[1], ResultTab.valueOf(parts[2]), parts[3].toIntOrNull(), parts.getOrNull(4).toBoolean())
        "file" -> Route.ResultFile(parts[1])
        "item" -> Route.ProtocolItem(parts[1], parts[2].toInt())
        else -> Route.Home
    }
}
