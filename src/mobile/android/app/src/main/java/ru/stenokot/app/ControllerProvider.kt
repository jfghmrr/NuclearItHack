package ru.stenokot.app

import android.content.Context
import android.content.Intent
import ru.stenokot.app.audio.MicSource
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.nav.Route

/** Подмена контроллера и стартового экрана. Реализация есть только в отладочной сборке. */
interface ControllerOverride {
    /** Контроллер вместо рабочего. Значение null оставляет рабочий. */
    fun controller(context: Context, intent: Intent?): StenoController?

    /** Экран, который просит открыть [intent]. */
    fun routeFor(intent: Intent?): Route?

    /** Источник звука вместо микрофона: у эмулятора без звука настоящей речи не взять. */
    fun micSource(): MicSource? = null
}

/**
 * Источник контроллера для активности. Рабочий контроллер подключается через [production]
 * при старте приложения, отладочная сборка ставит [override] своим провайдером содержимого.
 */
object ControllerProvider {
    /** Создаёт рабочий контроллер. */
    @Volatile
    var production: ((Context) -> StenoController)? = null

    /** Подмена отладочной сборки. В релизной сборке всегда null. */
    @Volatile
    var override: ControllerOverride? = null

    private var instance: StenoController? = null

    /** Контроллер процесса. Создаётся один раз. */
    @Synchronized
    fun get(context: Context, intent: Intent?): StenoController =
        instance ?: resolve(context.applicationContext, intent).also { instance = it }

    private fun resolve(context: Context, intent: Intent?): StenoController =
        override?.controller(context, intent)
            ?: production?.invoke(context)
            ?: InertController()
}
