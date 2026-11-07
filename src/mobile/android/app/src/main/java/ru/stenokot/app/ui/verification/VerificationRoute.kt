package ru.stenokot.app.ui.verification

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ru.stenokot.app.R
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.components.StenoTopBar

/** SCR-08. Здесь только каркас, экран подключается отдельно. */
@Composable
fun VerificationRoute(controller: StenoController, actions: AppActions) {
    Column(Modifier.fillMaxSize()) {
        StenoTopBar(stringResource(R.string.common_btn_models), onBack = actions.back)
    }
}
