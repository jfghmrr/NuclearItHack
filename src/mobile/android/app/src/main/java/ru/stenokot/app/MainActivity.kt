package ru.stenokot.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.pipeline.IntentEntry
import ru.stenokot.app.ui.StenoApp
import ru.stenokot.app.ui.nav.Route

/** Единственная активность приложения: принимает входящие аудио и показывает интерфейс. */
class MainActivity : ComponentActivity() {
    private val externalRoute = mutableStateOf<Route?>(null)
    private lateinit var controller: StenoController
    private lateinit var entry: IntentEntry

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false
        controller = ControllerProvider.get(applicationContext, intent)
        entry = IntentEntry(controller)
        setContent {
            StenoApp(
                activity = this,
                controller = controller,
                externalRoute = externalRoute.value,
                onExternalRouteHandled = { externalRoute.value = null },
            )
        }
        openIncoming(intent) { entry.onCreate(intent, savedInstanceState) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openIncoming(intent) { entry.onNewIntent(intent) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        entry.onSaveInstanceState(outState)
    }

    /** Открытие файла из другого приложения сразу ведёт на обработку, без лишних шагов. */
    private fun openIncoming(intent: Intent?, import: () -> String?) {
        val debugRoute = ControllerProvider.override?.routeFor(intent)
        if (debugRoute != null) {
            externalRoute.value = debugRoute
            return
        }
        import()?.let { externalRoute.value = Route.Processing(it) }
    }
}
