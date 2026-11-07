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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import ru.stenokot.app.ui.components.CenteredState
import ru.stenokot.app.ui.components.MascotPlate
import ru.stenokot.app.ui.components.StateTextWidth
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
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.nav.Route
import ru.stenokot.app.ui.nav.routeForJob
import ru.stenokot.app.ui.theme.EnterMotion
import ru.stenokot.app.ui.theme.StenoMotion
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.core.model.ErrorCode
import ru.stenokot.core.model.JobState

private const val META_DOT = " · "
private val IconCircle = 96.dp

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
    FailureAction.MODELS -> ActionLook(R.string.scr07_action_models, R.drawable.ic_memory, Tags.OPEN_MODELS)
    FailureAction.RETRY -> when (kind) {
        FailureKind.STORAGE -> ActionLook(R.string.scr07_action_recheck, R.drawable.ic_replay, Tags.START)
        FailureKind.MEMORY -> ActionLook(R.string.scr07_action_safe_retry, R.drawable.ic_replay, Tags.START)
        FailureKind.INTERRUPTED -> ActionLook(R.string.common_btn_resume, R.drawable.ic_play_arrow_fill, Tags.START)
        else -> ActionLook(R.string.common_btn_start, R.drawable.ic_play_arrow_fill, Tags.START)
    }
}

/** Подпись верхней панели: где находится человек, сам заголовок экрана говорит, что делать. */
@StringRes
private fun barTitle(kind: FailureKind): Int = when (kind) {
    FailureKind.MODEL -> R.string.scr07_bar_model
    FailureKind.FORMAT, FailureKind.CORRUPT, FailureKind.READ, FailureKind.NO_PERMISSION -> R.string.scr07_bar_file
    FailureKind.STORAGE -> R.string.scr07_bar_storage
    FailureKind.MEMORY -> R.string.scr07_bar_memory
    FailureKind.PROCESSING -> R.string.scr07_title
    FailureKind.INTERRUPTED -> R.string.scr07_bar_interrupted
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
        onLibrary = { actions.navigator.resetTo(Route.Home) },
        onAction = { action ->
            when (action) {
                FailureAction.IMPORT -> actions.importAudio()
                FailureAction.MODELS -> actions.navigator.push(Route.Models)
                FailureAction.RETRY ->
                    if (state is JobState.Interrupted) controller.resume(jobId) else controller.start(jobId)
            }
        },
    )
}

/** Кнопки причины: главная внизу экрана, тихие под ней. Куда что встанет, решает окно. */
private class FailureCallbacks(val onAction: (FailureAction) -> Unit, val onLibrary: () -> Unit)

@Composable
private fun FailureScreen(
    detail: JobDetailUi,
    kind: FailureKind,
    copy: DiagnosticsCopy,
    onBack: () -> Unit,
    onLibrary: () -> Unit,
    onAction: (FailureAction) -> Unit,
) {
    val layout = LocalWindowLayout.current
    val colors = StenoTheme.colors
    val pane = stringResource(R.string.scr07_title)
    val bar = stringResource(barTitle(kind))
    val text = failureText(kind, detail)
    val callbacks = remember(onAction, onLibrary) { FailureCallbacks(onAction, onLibrary) }
    val rootModifier = Modifier.fillMaxSize().semantics { paneTitle = pane }
    val plate = if (layout.short || layout.largeText) StenoSize.mascotPlateSmall else StenoSize.mascotPlate
    val quietInDock = !layout.hintsInContent
    val dock: @Composable () -> Unit = {
        if (!layout.tiny) {
            ActionDock(centered = !layout.twoPane) {
                FailureButtons(
                    kind = kind,
                    primary = true,
                    quiet = quietInDock,
                    modifier = Modifier.padding(horizontal = layout.gutter).padding(top = StenoSpace.m, bottom = StenoSpace.s),
                    callbacks = callbacks,
                )
            }
        }
    }
    val inlineActions: @Composable () -> Unit = {
        if (layout.tiny || !quietInDock) {
            FailureButtons(
                kind = kind,
                primary = layout.tiny,
                quiet = true,
                modifier = Modifier.padding(top = StenoSpace.s),
                callbacks = callbacks,
            )
        }
    }
    if (layout.twoPane) {
        Row(rootModifier) {
            Column(Modifier.weight(0.42f).fillMaxHeight()) {
                StenoTopBar(bar, onBack = onBack)
                CenteredState(Modifier.weight(1f).fillMaxWidth()) {
                    FailureArt(kind, plate)
                    FailureFile(detail)
                }
            }
            Spacer(Modifier.width(StenoSize.hairline).fillMaxHeight().background(colors.outlineVariant))
            Column(Modifier.weight(0.58f).fillMaxHeight()) {
                CenteredState(Modifier.weight(1f).fillMaxWidth()) {
                    FailureMessage(text)
                    DetailsBlock(detail, copy)
                    inlineActions()
                }
                dock()
            }
        }
    } else {
        Column(rootModifier) {
            StenoTopBar(bar, onBack = onBack)
            CenteredState(Modifier.weight(1f).fillMaxWidth()) {
                FailureArt(kind, plate)
                FailureMessage(text)
                FailureFile(detail)
                DetailsBlock(detail, copy)
                inlineActions()
            }
            dock()
        }
    }
}

/** Имя файла и его данные: что именно не получилось обработать. */
@Composable
private fun FailureFile(detail: JobDetailUi) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val preflight = detail.processing?.preflight
    val duration = (preflight?.durationMs ?: detail.summary.durationMs)?.let(::formatTimecode)
    val size = preflight?.sizeBytes?.let(::formatMib)
    val meta = listOfNotNull(duration, size).joinToString(META_DOT)
    Column(
        modifier = Modifier.widthIn(max = StateTextWidth).padding(top = StenoSpace.xs).testTag(Tags.CARD_FILE),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = preflight?.fileName ?: detail.summary.title,
            modifier = Modifier.testTag(Tags.FILE_NAME),
            style = type.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        if (meta.isNotEmpty()) Text(meta, style = type.bodySmall, color = colors.onSurfaceVariant)
    }
}

/** Иллюстрация причины. Сбой модели и проверки показывается значком: коту там нечего делать. */
@Composable
private fun FailureArt(kind: FailureKind, plate: Dp) {
    val colors = StenoTheme.colors
    val mascot = kind.mascot
    if (mascot != null) {
        MascotPlate(mascot, Modifier.padding(bottom = StenoSpace.s), plate = plate, motion = EnterMotion.ERROR)
        return
    }
    val critical = kind == FailureKind.PROCESSING
    Box(
        modifier = Modifier
            .padding(bottom = StenoSpace.s)
            .size(IconCircle)
            .background(colors.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        StenoIcon(
            icon = if (critical) R.drawable.ic_error else R.drawable.ic_memory,
            tint = if (critical) colors.error else colors.primary,
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
            .widthIn(max = StateTextWidth)
            .semantics { heading() }
            .testTag(Tags.ERROR_TITLE),
        style = if (LocalWindowLayout.current.largeText) type.titleLarge else type.headline,
        color = colors.onSurface,
        textAlign = TextAlign.Center,
    )
    Text(
        text = text.body,
        modifier = Modifier.widthIn(max = StateTextWidth).testTag(Tags.ERROR_BODY),
        style = type.bodyLarge,
        color = colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsBlock(detail: JobDetailUi, copy: DiagnosticsCopy) {
    var open by rememberSaveable(key = "failure_details") { mutableStateOf(false) }
    Column(Modifier.widthIn(max = StateTextWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(StenoSpace.m, Alignment.CenterHorizontally),
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
                tag = Tags.COPY_REPORT,
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

/**
 * Главное действие и тихие под ним: запасное действие причины и возврат к записям. На тесном экране
 * тихие уходят в прокрутку, чтобы текст остался на виду.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FailureButtons(
    kind: FailureKind,
    primary: Boolean,
    quiet: Boolean,
    modifier: Modifier,
    callbacks: FailureCallbacks,
) {
    val pair = kind.actions
    val spare = pair.secondary?.let { it to actionLook(it, kind) }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (primary) {
            val look = actionLook(pair.primary, kind)
            PrimaryAction(
                text = stringResource(look.label),
                onClick = { callbacks.onAction(pair.primary) },
                modifier = Modifier.fillMaxWidth().widthIn(max = StateTextWidth),
                icon = look.icon,
                tag = look.tag,
            )
        }
        if (quiet) {
            FlowRow(
                modifier = Modifier.padding(top = StenoSpace.xs),
                horizontalArrangement = Arrangement.spacedBy(StenoSpace.m, Alignment.CenterHorizontally),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                spare?.let { (action, look) ->
                    QuietAction(stringResource(look.label), { callbacks.onAction(action) }, tag = look.tag)
                }
                QuietAction(stringResource(R.string.scr03_to_library), callbacks.onLibrary, tag = Tags.TO_LIBRARY)
            }
        }
    }
}
