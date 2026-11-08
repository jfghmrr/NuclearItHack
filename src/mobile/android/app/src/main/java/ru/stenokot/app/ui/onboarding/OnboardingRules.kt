package ru.stenokot.app.ui.onboarding

import ru.stenokot.core.model.JobState

/** Что делать со знакомством при запуске или при смене состояния приложения. */
enum class OnboardingDecision {
    /** Показать знакомство. */
    SHOW,

    /** Не показывать сейчас, флаг не трогать: например, открыт отладочный сценарий. */
    HIDE,

    /** Не показывать и больше не предлагать: человек уже работает с файлом. */
    HIDE_FOR_GOOD,
}

/**
 * Когда показывать знакомство. Правила отделены от Android, чтобы их можно было проверить на JVM.
 *
 * Знакомство не должно мешать автоматике: запуск через VIEW, SEND и SEND_MULTIPLE (так файл открывает
 * `adb shell am start`) и любой идущий импорт его отменяют сразу и навсегда, файл обрабатывается без
 * задержки.
 */
object OnboardingRules {
    /** Действия, с которыми приложение открывают ради конкретного файла. */
    val FILE_ACTIONS: Set<String> = setOf(
        "android.intent.action.VIEW",
        "android.intent.action.SEND",
        "android.intent.action.SEND_MULTIPLE",
    )

    /**
     * Решение по состоянию запуска. [seen] это сохранённый флаг, [launchAction] действие стартового
     * интента, [externalRoute] означает, что снаружи уже пришёл экран (файл или отладочный сценарий),
     * [debugScene] что этот экран пришёл из отладочной сборки, [jobs] задачи в библиотеке: готовые
     * записи знакомству не мешают, идущие отменяют его,
     * [recording] идёт ли запись с микрофона.
     */
    fun decide(
        seen: Boolean,
        launchAction: String?,
        externalRoute: Boolean,
        debugScene: Boolean,
        jobs: List<JobState>,
        recording: Boolean,
    ): OnboardingDecision = when {
        seen -> OnboardingDecision.HIDE
        debugScene -> OnboardingDecision.HIDE
        launchAction in FILE_ACTIONS -> OnboardingDecision.HIDE_FOR_GOOD
        externalRoute -> OnboardingDecision.HIDE_FOR_GOOD
        recording -> OnboardingDecision.HIDE_FOR_GOOD
        jobs.any(::isActive) -> OnboardingDecision.HIDE_FOR_GOOD
        else -> OnboardingDecision.SHOW
    }

    /**
     * Нужно ли убрать уже открытое знакомство: пришёл файл снаружи, началась запись или в библиотеке
     * появилась идущая задача. Работает и при повторном показе из «Помощи».
     */
    fun interrupted(externalRoute: Boolean, jobs: List<JobState>, recording: Boolean): Boolean =
        externalRoute || recording || jobs.any(::isActive)

    /** Задача ещё идёт или ждёт продолжения: импорт в процессе. */
    fun isActive(state: JobState): Boolean = when (state) {
        JobState.Queued, is JobState.Running, is JobState.Paused, JobState.WaitingModel -> true
        else -> false
    }
}
