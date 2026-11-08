package ru.stenokot.app.ui

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import ru.stenokot.app.ControllerProvider
import ru.stenokot.app.domain.RecordingUi
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.components.StatusLine
import ru.stenokot.app.ui.components.resourceTags
import ru.stenokot.app.ui.failure.FailureRoute
import ru.stenokot.app.ui.format.STATUS_IDLE
import ru.stenokot.app.ui.format.STATUS_RECORDING
import ru.stenokot.app.ui.format.statusCode
import ru.stenokot.app.ui.help.HelpRoute
import ru.stenokot.app.ui.home.HomeRoute
import ru.stenokot.app.ui.layout.ProvideWindowLayout
import ru.stenokot.app.ui.layout.screenInsets
import ru.stenokot.app.ui.licenses.LicenseTextRoute
import ru.stenokot.app.ui.licenses.LicensesRoute
import ru.stenokot.app.ui.nav.Route
import ru.stenokot.app.ui.onboarding.OnboardingRules
import ru.stenokot.app.ui.onboarding.OnboardingScreen
import ru.stenokot.app.ui.onboarding.OnboardingWatch
import ru.stenokot.app.ui.onboarding.rememberOnboardingState
import ru.stenokot.app.ui.nav.rememberNavigator
import ru.stenokot.app.ui.nav.routeForJob
import ru.stenokot.app.ui.nav.stateKey
import ru.stenokot.app.ui.nav.Navigator
import ru.stenokot.app.ui.processing.ProcessingRoute
import ru.stenokot.app.ui.record.MicPermissionRoute
import ru.stenokot.app.ui.record.RecordRoute
import ru.stenokot.app.ui.result.ProtocolItemRoute
import ru.stenokot.app.ui.result.ResultFileRoute
import ru.stenokot.app.ui.result.ResultRoute
import ru.stenokot.app.ui.settings.SettingsRoute
import ru.stenokot.app.ui.theme.LocalAppearance
import ru.stenokot.app.ui.theme.LocalMotionEnabled
import ru.stenokot.app.ui.theme.StenoMotion
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.app.ui.theme.appearanceSettings
import ru.stenokot.app.ui.theme.rememberSystemMotion
import ru.stenokot.app.ui.verification.AboutRoute
import ru.stenokot.app.ui.verification.DiagnosticsRoute
import ru.stenokot.app.ui.verification.ModelCardRoute
import ru.stenokot.app.ui.verification.ModelsRoute
import ru.stenokot.app.ui.verification.PermissionsRoute
import ru.stenokot.app.ui.verification.PrivacyRoute
import ru.stenokot.core.model.JobState
import ru.stenokot.core.model.Stage

/** Идентификатор задачи, которую показывает маршрут. */
/** Сдвиг экрана при переходе: двадцатая часть ширины, чтобы движение читалось, но не мешало. */
private const val SCREEN_SHIFT = 20

private val Route.jobId: String?
    get() = when (this) {
        is Route.Processing -> jobId
        is Route.Result -> jobId
        is Route.ResultFile -> jobId
        is Route.ProtocolItem -> jobId
        is Route.Failure -> jobId
        Route.Home, Route.Record, Route.MicPermission, Route.Settings, Route.Help,
        Route.Models, is Route.ModelCard, Route.Privacy, Route.Permissions,
        Route.Diagnostics, Route.About, Route.Licenses, is Route.LicenseText,
        -> null
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
    val appearance = remember(activity) { appearanceSettings(activity) }
    StenoTheme(appearance.theme) {
        val dark = StenoTheme.colors.isDark
        val view = LocalView.current
        SideEffect {
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        CompositionLocalProvider(
            LocalAppearance provides appearance,
            LocalMotionEnabled provides (appearance.mascotMotion && rememberSystemMotion()),
        ) {
            ProvideWindowLayout(activity) { StenoShell(activity, controller, externalRoute, onExternalRouteHandled) }
        }
    }
}

@Composable
private fun StenoShell(
    activity: Activity,
    controller: StenoController,
    externalRoute: Route?,
    onExternalRouteHandled: () -> Unit,
) {
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
    val onboarding = rememberOnboardingState { seen ->
        OnboardingRules.decide(
            seen = seen,
            launchAction = activity.intent?.action,
            externalRoute = externalRoute != null,
            debugScene = ControllerProvider.override?.routeFor(activity.intent) != null,
            jobs = controller.home.value.jobs.map { it.state },
            recording = controller.recording.value !is RecordingUi.Idle,
        )
    }
    OnboardingWatch(onboarding, controller, externalRoute != null)

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

    LaunchedEffect(controller, navigator) {
        controller.recording.collect { followRecording(it, controller, navigator) }
    }

    BackHandler(enabled = navigator.canGoBack) { navigator.back() }

    val depth = navigator.depth
    var shownDepth by remember { mutableIntStateOf(depth) }
    val forward = depth >= shownDepth
    SideEffect { shownDepth = depth }

    Column(
        Modifier
            .fillMaxSize()
            .background(StenoTheme.colors.background)
            .resourceTags(),
    ) {
        if (onboarding.visible) {
            OnboardingScreen(onboarding, onImport = actions.importAudio, modifier = Modifier.weight(1f).screenInsets())
        } else AnimatedContent(
            targetState = navigator.current,
            modifier = Modifier.weight(1f).screenInsets(),
            transitionSpec = {
                val sign = if (forward) 1 else -1
                val enter = tween<IntOffset>(StenoMotion.MEDIUM, easing = FastOutSlowInEasing)
                val exit = tween<IntOffset>(StenoMotion.SHORT, easing = FastOutSlowInEasing)
                (fadeIn(tween(StenoMotion.MEDIUM)) + slideInHorizontally(enter) { sign * it / SCREEN_SHIFT }) togetherWith
                    (fadeOut(tween(StenoMotion.SHORT)) + slideOutHorizontally(exit) { -sign * it / SCREEN_SHIFT })
            },
            contentKey = { it::class },
            label = "screen",
        ) { route ->
            screens.SaveableStateProvider(route.stateKey) {
                when (route) {
                    Route.Home -> HomeRoute(controller, actions)
                    Route.Record -> RecordRoute(controller)
                    Route.MicPermission -> MicPermissionRoute(controller, actions)
                    Route.Settings -> SettingsRoute(controller, actions)
                    is Route.Processing -> ProcessingRoute(route.jobId, controller, actions)
                    is Route.Result -> ResultRoute(route, controller, actions)
                    is Route.ResultFile -> ResultFileRoute(route, controller, actions)
                    is Route.ProtocolItem -> ProtocolItemRoute(route, controller, actions)
                    is Route.Failure -> FailureRoute(route.jobId, controller, actions)
                    Route.Models -> ModelsRoute(controller, actions)
                    is Route.ModelCard -> ModelCardRoute(route.name, controller, actions)
                    Route.Privacy -> PrivacyRoute(controller, actions)
                    Route.Permissions -> PermissionsRoute(controller, actions)
                    Route.Diagnostics -> DiagnosticsRoute(controller, actions)
                    Route.About -> AboutRoute(actions)
                    Route.Licenses -> LicensesRoute(actions)
                    is Route.LicenseText -> LicenseTextRoute(route.entryId, actions)
                    Route.Help -> HelpRoute(actions, onReplayIntro = onboarding::replay)
                }
            }
        }
        StatusLine(rememberStatusCode(controller, navigator.current))
    }
}

/**
 * Пока идёт запись, её экран всегда сверху: с него есть стоп, и случайно потерять запись негде. Экран
 * может быть восстановлен и без записи, тогда он сам закрывается. Готовая запись ведёт на свою задачу.
 */
private fun followRecording(state: RecordingUi, controller: StenoController, navigator: Navigator) {
    val onRecord = navigator.current == Route.Record
    when (state) {
        RecordingUi.Starting, is RecordingUi.Active -> navigator.push(Route.Record)
        is RecordingUi.Saved -> {
            val target = Route.Processing(state.jobId)
            if (onRecord) navigator.replaceTop(target) else navigator.push(target)
            controller.acknowledgeRecording()
        }
        RecordingUi.Idle, is RecordingUi.Failed -> if (onRecord) navigator.back()
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
    if (route == Route.Record) return STATUS_RECORDING
    val id = route.jobId ?: return home.statusCode.ifEmpty { STATUS_IDLE }
    val job by remember(id) { controller.job(id) }.collectAsStateWithLifecycle()
    return job?.summary?.state?.let(::statusCode) ?: home.statusCode.ifEmpty { STATUS_IDLE }
}
