package ru.stenokot.app.ui.failure

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.stenokot.app.R
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.components.StenoTopBar

/** SCR-07. Здесь только каркас, экран подключается отдельно. */
@Composable
fun FailureRoute(jobId: String, controller: StenoController, actions: AppActions) {
    Column(Modifier.fillMaxSize()) {
        StenoTopBar(stringResource(R.string.scr03_title), onBack = actions.back)
    }
}
