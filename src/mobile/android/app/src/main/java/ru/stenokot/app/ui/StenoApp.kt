package ru.stenokot.app.ui

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.components.StatusLine
import ru.stenokot.app.ui.components.resourceTags
import ru.stenokot.app.ui.failure.FailureRoute
import ru.stenokot.app.ui.format.STATUS_IDLE
import ru.stenokot.app.ui.format.statusCode
import ru.stenokot.app.ui.home.HomeRoute
import ru.stenokot.app.ui.layout.ProvideWindowLayout
import ru.stenokot.app.ui.layout.screenInsets
import ru.stenokot.app.ui.licenses.LicenseTextRoute
import ru.stenokot.app.ui.licenses.LicensesRoute
import ru.stenokot.app.ui.nav.Route
import ru.stenokot.app.ui.nav.rememberNavigator
import ru.stenokot.app.ui.nav.routeForJob
import ru.stenokot.app.ui.nav.stateKey
import ru.stenokot.app.ui.processing.ProcessingRoute
import ru.stenokot.app.ui.result.ResultRoute
import ru.stenokot.app.ui.theme.StenoMotion
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.app.ui.verification.VerificationRoute
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.Stage

/** Идентификатор задачи, которую показывает маршрут. */
private val Route.jobId: String?
    get() = when (this) {
        is Route.Processing -> jobId
        is Route.Result -> jobId
        is Route.Failure -> jobId
        Route.Home, Route.Verification, Route.Licenses, is Route.LicenseText -> null
    }

/**
 * Корень интерфейса: тема, раскладка окна, стек экранов и постоянная строка `txt_status`.
 * Внешние переходы (открытие файла из другого приложения) приходят через [externalRoute].
 */
@Composable
fun StenoApp(
    activity: Activity,
    controller: StenoController,
    externalRoute: Route?,
    onExternalRouteHandled: () -> Unit,
) {
    StenoTheme {
        ProvideWindowLayout(activity) {
            val navigator = rememberNavigator()
            val screens = rememberSaveableStateHolder()
            var awaitedJob by rememberSaveable { mutableStateOf<String?>(null) }

            val importFiles: (List<Uri>) -> Unit = remember(controller) {
                { uris ->
                    val busy = controller.home.value.jobs.any { it.state.isHeavy() }
                    val id = controller.import(uris)
                    if (id != null && !busy) awaitedJob = id
                }
            }
            val openPicker = rememberAudioPicker(importFiles)
            val actions = remember(navigator, openPicker) { AppActions(navigator, openPicker, importFiles) }

            LaunchedEffect(externalRoute) {
                if (externalRoute != null) {
                    navigator.resetTo(externalRoute)
                    onExternalRouteHandled()
                }
            }
            LaunchedEffect(awaitedJob) {
                val id = awaitedJob ?: return@LaunchedEffect
                val started = controller.job(id).filterNotNull().first { it.summary.state != JobState.Queued }
                val target = routeForJob(id, started.summary.state)
                if (navigator.current is Route.Failure) navigator.replaceTop(target) else navigator.push(target)
                awaitedJob = null
            }

            BackHandler(enabled = navigator.canGoBack) { navigator.back() }

            Column(
                Modifier
                    .fillMaxSize()
                    .background(StenoTheme.colors.background)
                    .resourceTags(),
            ) {
                AnimatedContent(
                    targetState = navigator.current,
                    modifier = Modifier.weight(1f).screenInsets(),
                    transitionSpec = {
                        fadeIn(tween(StenoMotion.MEDIUM)) togetherWith fadeOut(tween(StenoMotion.SHORT))
                    },
                    contentKey = { it::class },
                    label = "screen",
                ) { route ->
                    screens.SaveableStateProvider(route.stateKey) {
                        when (route) {
                            Route.Home -> HomeRoute(controller, actions)
                            is Route.Processing -> ProcessingRoute(route.jobId, controller, actions)
                            is Route.Result -> ResultRoute(route, controller, actions)
                            is Route.Failure -> FailureRoute(route.jobId, controller, actions)
                            Route.Verification -> VerificationRoute(controller, actions)
                            Route.Licenses -> LicensesRoute(actions)
                            is Route.LicenseText -> LicenseTextRoute(route.entryId, actions)
                        }
                    }
                }
                StatusLine(rememberStatusCode(controller, navigator.current))
            }
        }
    }
}

/** Единственная стадия, ради которой новый файл встаёт в очередь: тяжёлый конвейер уже занят. */
private fun JobState.isHeavy(): Boolean = when (this) {
    is JobState.Running -> stage != Stage.LLM
    is JobState.Paused, JobState.Queued -> true
    else -> false
}

/** Код строки состояния: на экранах задачи её состояние, иначе общее состояние приложения. */
@Composable
private fun rememberStatusCode(controller: StenoController, route: Route): String {
    val home by controller.home.collectAsStateWithLifecycle()
    val id = route.jobId ?: return home.statusCode.ifEmpty { STATUS_IDLE }
    val job by remember(id) { controller.job(id) }.collectAsStateWithLifecycle()
    return job?.summary?.state?.let(::statusCode) ?: home.statusCode.ifEmpty { STATUS_IDLE }
}
