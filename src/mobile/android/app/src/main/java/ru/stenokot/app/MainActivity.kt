package ru.stenokot.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.StenoApp
import ru.stenokot.app.ui.nav.Route

/** Единственная активность приложения: принимает входящие аудио и показывает интерфейс. */
class MainActivity : ComponentActivity() {
    private val externalRoute = mutableStateOf<Route?>(null)
    private lateinit var controller: StenoController

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        controller = ControllerProvider.get(applicationContext, intent)
        setContent {
            StenoApp(
                activity = this,
                controller = controller,
                externalRoute = externalRoute.value,
                onExternalRouteHandled = { externalRoute.value = null },
            )
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Открытие файла из другого приложения сразу ведёт на обработку, без лишних шагов. */
    private fun handleIntent(intent: Intent?) {
        val debugRoute = ControllerProvider.override?.routeFor(intent)
        if (debugRoute != null) {
            externalRoute.value = debugRoute
            return
        }
        val uris = incomingUris(intent)
        if (uris.isEmpty()) return
        controller.import(uris)?.let { externalRoute.value = Route.Processing(it) }
    }

    private fun incomingUris(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND -> listOfNotNull(streamExtra(intent))
        Intent.ACTION_SEND_MULTIPLE -> streamListExtra(intent)
        else -> emptyList()
    }

    private fun streamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun streamListExtra(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
}
