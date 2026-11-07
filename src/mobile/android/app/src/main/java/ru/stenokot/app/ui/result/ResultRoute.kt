package ru.stenokot.app.ui.result

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.components.StenoTopBar
import ru.stenokot.app.ui.nav.Route

/** SCR-04 и SCR-05. Здесь только каркас, экран подключается отдельно. */
@Composable
fun ResultRoute(route: Route.Result, controller: StenoController, actions: AppActions) {
    val job by remember(route.jobId) { controller.job(route.jobId) }.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        StenoTopBar(job?.summary?.title.orEmpty(), onBack = actions.back)
    }
}
