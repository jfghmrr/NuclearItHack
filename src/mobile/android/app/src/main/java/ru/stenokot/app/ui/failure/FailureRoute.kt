package ru.stenokot.app.ui.failure

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.stenokot.app.R
import ru.stenokot.app.domain.JobDetailUi
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.ActionDock
import ru.stenokot.app.ui.components.DiagnosticsCopy
import ru.stenokot.app.ui.components.FileCard
import ru.stenokot.app.ui.components.MascotAtStart
import ru.stenokot.app.ui.components.OutlinedAction
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.QuietAction
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.StenoTopBar
import ru.stenokot.app.ui.components.rememberDiagnosticsCopy
import ru.stenokot.app.ui.format.formatFreeSpace
import ru.stenokot.app.ui.format.formatMib
import ru.stenokot.app.ui.format.formatSpaceNeeded
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.app.ui.format.stageLabel
import ru.stenokot.app.ui.format.statusCode
import ru.stenokot.app.ui.layout.CenteredContent
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.nav.Route
import ru.stenokot.app.ui.nav.routeForJob
import ru.stenokot.app.ui.theme.StenoMotion
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.JobState

private const val META_DOT = " · "
private val ArtShort = 88.dp
private val ArtWide = 160.dp
private val IconCircle = 72.dp

/** Подпись и значок одной кнопки. */
private class ActionLook(@StringRes val label: Int, @DrawableRes val icon: Int, val tag: String)

private fun actionLook(action: FailureAction, kind: FailureKind): ActionLook = when (action) {
    FailureAction.IMPORT -> ActionLook(
        label = when (kind) {
            FailureKind.FORMAT, FailureKind.CORRUPT -> R.string.scr07_action_other_file
            else -> R.string.common_btn_import
        },
        icon = R.drawable.ic_upload_file,
        tag = Tags.IMPORT,
    )
    FailureAction.DIAGNOSTICS -> ActionLook(R.string.scr07_action_diagnostics, R.drawable.ic_chevron_right, Tags.OPEN_MODELS)
    FailureAction.RETRY -> when (kind) {
        FailureKind.STORAGE -> ActionLook(R.string.scr07_action_recheck, R.drawable.ic_replay, Tags.START)
        FailureKind.MEMORY -> ActionLook(R.string.scr07_action_safe_retry, R.drawable.ic_replay, Tags.START)
        FailureKind.INTERRUPTED -> ActionLook(R.string.common_btn_resume, R.drawable.ic_play_arrow_fill, Tags.START)
        else -> ActionLook(R.string.common_btn_start, R.drawable.ic_play_arrow_fill, Tags.START)
    }
}

/** Заголовок и причина. */
private class FailureText(val title: String, val body: String)

@Composable
private fun failureText(kind: FailureKind, detail: JobDetailUi): FailureText {
    val state = detail.summary.state
    val name = detail.summary.title
    val stage = failureStage(state)?.let(::stageLabel).orEmpty()
    val preflight = detail.processing?.preflight
    val toFree = bytesToFree(preflight)
    return when (kind) {
        FailureKind.MODEL -> FailureText(
            stringResource(R.string.scr07_model_title),
            stringResource(R.string.scr07_model_body),
        )
        FailureKind.FORMAT -> FailureText(
            stringResource(R.string.scr07_format_title),
            stringResource(R.string.scr07_format_body, name),
        )
        FailureKind.CORRUPT -> FailureText(
            stringResource(R.string.scr07_corrupt_title),
            stringResource(R.string.scr07_corrupt_body, name),
        )
        FailureKind.READ -> FailureText(
            stringResource(R.string.scr07_read_title),
            stringResource(R.string.scr07_read_body, name),
        )
        FailureKind.NO_PERMISSION -> FailureText(
            stringResource(R.string.scr07_read_title),
            stringResource(R.string.scr07_permission_body),
        )
        FailureKind.STORAGE -> if (toFree != null && preflight != null) {
            FailureText(
                stringResource(R.string.scr07_storage_free_title, formatSpaceNeeded(toFree)),
                stringResource(
                    R.string.scr07_storage_free_body,
                    formatFreeSpace(preflight.freeBytes),
                    formatSpaceNeeded(preflight.requiredBytes),
                ),
            )
        } else {
            FailureText(
                stringResource(R.string.scr07_storage_title),
                stringResource(R.string.scr07_storage_body),
            )
        }
        FailureKind.MEMORY -> FailureText(
            stringResource(R.string.scr07_memory_title),
            stringResource(R.string.scr07_memory_body, stage),
        )
        FailureKind.PROCESSING -> FailureText(
            stringResource(R.string.scr07_processing_title),
            if ((state as? JobState.Failed)?.code == ErrorCode.CONTRACT_INVALID || stage.isEmpty()) {
                stringResource(R.string.scr07_processing_body)
            } else {
                stringResource(R.string.scr07_stage_body, stage)
            },
        )
        FailureKind.INTERRUPTED -> FailureText(
            stringResource(R.string.scr07_interrupted_title),
            stringResource(R.string.scr07_interrupted_body, stage),
        )
    }
}

/** SCR-07. Сам уходит на обработку или результат, как только задача снова пошла. */
@Composable
fun FailureRoute(jobId: String, controller: StenoController, actions: AppActions) {
    val job by remember(jobId) { controller.job(jobId) }.collectAsStateWithLifecycle()
    val detail = job
    val state = detail?.summary?.state
    LaunchedEffect(state) {
        if (state == null) return@LaunchedEffect
        val target = routeForJob(jobId, state)
        if (target !is Route.Failure) actions.navigator.replaceTop(target)
    }
    val kind = state?.let(::failureKind)
    if (detail == null || kind == null) {
        Column(Modifier.fillMaxSize()) {
            StenoTopBar(stringResource(R.string.scr07_title), onBack = actions.back)
        }
        return
    }
    val copy = rememberDiagnosticsCopy(controller, jobId)
    FailureScreen(
        detail = detail,
        kind = kind,
        copy = copy,
        onBack = actions.back,
        onAction = { action ->
            when (action) {
                FailureAction.IMPORT -> actions.importAudio()
                FailureAction.DIAGNOSTICS -> actions.navigator.push(Route.Verification)
                FailureAction.RETRY ->
                    if (state is JobState.Interrupted) controller.resume(jobId) else controller.start(jobId)
            }
        },
    )
}

@Composable
private fun FailureScreen(
    detail: JobDetailUi,
    kind: FailureKind,
    copy: DiagnosticsCopy,
    onBack: () -> Unit,
    onAction: (FailureAction) -> Unit,
) {
    val layout = LocalWindowLayout.current
    val colors = StenoTheme.colors
    val state = detail.summary.state
    val pane = stringResource(R.string.scr07_title)
    val barTitle = stringResource(if (state is JobState.Interrupted) R.string.scr03_title else R.string.scr07_title)
    val text = failureText(kind, detail)
    val rootModifier = Modifier.fillMaxSize().semantics { paneTitle = pane }
    val artSize = when {
        layout.short || layout.largeText -> ArtShort
        layout.width == WindowWidthSizeClass.Expanded -> ArtWide
        else -> StenoSize.mascot
    }
    val dockBoth = !layout.short && !layout.largeText
    val file: @Composable () -> Unit = { FailureFile(detail) }
    val art: @Composable () -> Unit = { FailureArt(kind, artSize) }
    val message: @Composable ColumnScope.() -> Unit = {
        FailureMessage(text)
        DetailsBlock(detail, copy)
    }
    val dock: @Composable () -> Unit = {
        if (!layout.tiny) {
            ActionDock(centered = !layout.twoPane) {
                FailureButtons(
                    kind = kind,
                    primary = true,
                    secondary = dockBoth,
                    modifier = Modifier.padding(horizontal = layout.gutter, vertical = StenoSpace.m),
                    onAction = onAction,
                )
            }
        }
    }
    val inlineActions: @Composable () -> Unit = {
        if (layout.tiny || !dockBoth) {
            FailureButtons(
                kind = kind,
                primary = layout.tiny,
                secondary = true,
                modifier = Modifier.padding(top = StenoSpace.s),
                onAction = onAction,
            )
        }
    }
    if (layout.twoPane) {
        Row(rootModifier) {
            Column(Modifier.weight(0.42f).fillMaxHeight()) {
                StenoTopBar(barTitle, onBack = onBack)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = layout.gutter)
                        .padding(bottom = StenoSpace.l),
                    verticalArrangement = Arrangement.spacedBy(StenoSpace.m),
                ) {
                    art()
                    file()
                }
            }
            Spacer(Modifier.width(StenoSize.hairline).fillMaxHeight().background(colors.outlineVariant))
            Column(Modifier.weight(0.58f).fillMaxHeight()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = layout.gutter, vertical = StenoSpace.l),
                    verticalArrangement = Arrangement.spacedBy(StenoSpace.s),
                ) {
                    message()
                    inlineActions()
                }
                dock()
            }
        }
    } else {
        Column(rootModifier) {
            StenoTopBar(barTitle, onBack = onBack)
            CenteredContent(Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = layout.gutter)
                        .padding(bottom = StenoSpace.l),
                    verticalArrangement = Arrangement.spacedBy(StenoSpace.s),
                ) {
                    file()
                    Spacer(Modifier.padding(top = if (layout.short) 0.dp else StenoSpace.s))
                    art()
                    message()
                    inlineActions()
                }
            }
            dock()
        }
    }
}

@Composable
private fun FailureFile(detail: JobDetailUi) {
    val preflight = detail.processing?.preflight
    val duration = (preflight?.durationMs ?: detail.summary.durationMs)?.let(::formatTimecode)
    val size = preflight?.sizeBytes?.let(::formatMib)
    FileCard(
        name = preflight?.fileName ?: detail.summary.title,
        meta = listOfNotNull(duration, size).joinToString(META_DOT).ifEmpty { null },
    )
}

/** Иллюстрация причины. Сбой модели и проверки показывается значком: коту там нечего делать. */
@Composable
private fun FailureArt(kind: FailureKind, size: Dp) {
    val colors = StenoTheme.colors
    val mascot = kind.mascot
    if (mascot != null) {
        MascotAtStart(mascot, size = size)
        return
    }
    val critical = kind == FailureKind.PROCESSING
    Box(
        modifier = Modifier
            .padding(top = StenoSpace.s, bottom = StenoSpace.xs)
            .size(IconCircle)
            .background(colors.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        StenoIcon(
            icon = if (critical) R.drawable.ic_error else R.drawable.ic_report,
            tint = if (critical) colors.error else colors.onSurfaceVariant,
            size = StenoSize.iconLarge,
        )
    }
}

/** Заголовок получает фокус при открытии, поэтому TalkBack начинает с сути ошибки. */
@Composable
private fun FailureMessage(text: FailureText) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Text(
        text = text.title,
        modifier = Modifier
            .focusRequester(focus)
            .focusable()
            .semantics { heading() }
            .testTag(Tags.ERROR_TITLE),
        style = type.titleLarge,
        color = colors.onSurface,
    )
    Text(
        text = text.body,
        modifier = Modifier.testTag(Tags.ERROR_BODY),
        style = type.bodyLarge,
        color = colors.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsBlock(detail: JobDetailUi, copy: DiagnosticsCopy) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        FlowRow(
            modifier = Modifier.offset(x = -StenoSpace.s),
            horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            QuietAction(
                text = stringResource(if (open) R.string.scr07_less else R.string.scr07_more),
                onClick = { open = !open },
                tag = Tags.ERROR_DETAILS,
            )
            QuietAction(
                text = stringResource(if (copy.copied) R.string.scr07_copied else R.string.scr07_copy),
                onClick = copy::copy,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                tag = Tags.COPY_DIAGNOSTICS,
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(StenoMotion.MEDIUM)) + expandVertically(tween(StenoMotion.MEDIUM)),
            exit = fadeOut(tween(StenoMotion.SHORT)) + shrinkVertically(tween(StenoMotion.SHORT)),
        ) {
            TechnicalDetails(detail)
        }
    }
}

@Composable
private fun TechnicalDetails(detail: JobDetailUi) {
    val colors = StenoTheme.colors
    val state = detail.summary.state
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = StenoSpace.xs)
            .background(colors.surfaceContainerLow, RoundedCornerShape(StenoRadius.l))
            .testTag(Tags.ERROR_DETAILS_TEXT)
            .semantics(mergeDescendants = true) { }
            .padding(StenoSpace.m),
        verticalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        DetailLine(stringResource(R.string.scr07_detail_code), statusCode(state))
        failureStage(state)?.let { DetailLine(stringResource(R.string.scr07_detail_stage), it.name) }
        DetailLine(stringResource(R.string.scr07_detail_job), detail.summary.id)
        failureNote(state)?.let { DetailLine(stringResource(R.string.scr07_detail_note), it) }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    Column {
        Text(label, style = type.bodySmall, color = colors.onSurfaceVariant)
        Text(value, style = type.monoSmall, color = colors.onSurface)
    }
}

@Composable
private fun DockButton(action: FailureAction, kind: FailureKind, primary: Boolean, modifier: Modifier, onAction: (FailureAction) -> Unit) {
    val look = actionLook(action, kind)
    if (primary) {
        PrimaryAction(stringResource(look.label), { onAction(action) }, modifier, icon = look.icon, tag = look.tag)
    } else {
        OutlinedAction(stringResource(look.label), { onAction(action) }, modifier, icon = look.icon, tag = look.tag)
    }
}

/** Кнопки причины. На тесном экране запасная уходит в прокрутку, чтобы текст остался на виду. */
@Composable
private fun FailureButtons(
    kind: FailureKind,
    primary: Boolean,
    secondary: Boolean,
    modifier: Modifier,
    onAction: (FailureAction) -> Unit,
) {
    val layout = LocalWindowLayout.current
    val pair = kind.actions
    val spare = pair.secondary.takeIf { secondary }
    val first = pair.primary.takeIf { primary }
    if (first == null && spare == null) return
    if (first != null && spare != null && !layout.compactWidth && !layout.largeText) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
            DockButton(first, kind, true, Modifier.weight(1f), onAction)
            DockButton(spare, kind, false, Modifier.weight(1f), onAction)
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
            first?.let { DockButton(it, kind, true, Modifier, onAction) }
            spare?.let { DockButton(it, kind, false, Modifier, onAction) }
        }
    }
}
