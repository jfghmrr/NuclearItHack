package ru.stenokot.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import ru.stenokot.app.pipeline.AppGraph

/**
 * Только сборка объектов, без работы на диске: запуск приложения должен укладываться в секунду.
 * Процесс языковой модели поднимает тот же класс, поэтому конвейер запускается лишь в основном.
 */
class StenoApplication : Application() {
    /** Объекты приложения. В процессе языковой модели не создаются. */
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        if (getProcessName() != packageName) return
        ControllerProvider.production = { graph.controller }
        graph.start()
        registerActivityLifecycleCallbacks(ForegroundTracker { graph.controller.onForeground() })
    }

    /** Сообщает о выходе приложения на экран: только тогда система разрешает запуск службы. */
    private class ForegroundTracker(private val onForeground: () -> Unit) : ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            started += 1
            if (started == 1) onForeground()
        }

        override fun onActivityStopped(activity: Activity) {
            started -= 1
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
