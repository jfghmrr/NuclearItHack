package ru.stenokot.app.ui.result

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.stenokot.app.R
import ru.stenokot.app.domain.OutputFileUi
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.BottomActions
import ru.stenokot.app.ui.components.GhostAction
import ru.stenokot.app.ui.components.OutlinedAction
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.StatusChip
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.StenoTopBar
import ru.stenokot.app.ui.format.formatFileSize
import ru.stenokot.app.ui.format.formatHistoryDate
import ru.stenokot.app.ui.layout.CenteredContent
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.nav.Route
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private val FileTile = 64.dp

/** Строка сведений: подпись слева, значение справа. Длинное значение переносится, а не обрезается. */
@Composable
private fun FactLine(label: String, value: String) {
    val type = StenoTheme.type
    val colors = StenoTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.l),
    ) {
        Text(label, style = type.bodyMedium, color = colors.onSurfaceVariant)
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = type.labelMedium,
            color = colors.onSurface,
            textAlign = TextAlign.End,
        )
    }
    HorizontalDivider(thickness = StenoSize.hairline, color = colors.outlineVariant)
}

@Composable
private fun FileSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = StenoTheme.type.labelMedium,
            color = StenoTheme.colors.onSurfaceVariant,
        )
        content()
    }
}

/** Значок файла, имя и проверка контракта. */
@Composable
private fun FileHead(output: OutputFileUi) {
    val colors = StenoTheme.colors
    Box(
        modifier = Modifier
            .size(FileTile)
            .background(colors.primaryContainer, RoundedCornerShape(StenoRadius.card)),
        contentAlignment = Alignment.Center,
    ) {
        StenoIcon(R.drawable.ic_description, colors.primary, size = StenoSize.iconLarge)
    }
    Text(
        text = output.path.substringAfterLast('/'),
        modifier = Modifier.testTag(Tags.FILE_NAME),
        style = StenoTheme.type.titleLarge,
        color = colors.onSurface,
    )
    if (output.validated) {
        StatusChip(stringResource(R.string.scr04_file_valid), R.drawable.ic_check_circle, BannerTone.SUCCESS)
    } else {
        StatusChip(stringResource(R.string.scr04_json_unchecked), R.drawable.ic_warning, BannerTone.WARNING)
    }
}

/** Сведения о файле результата. Служебные данные живут здесь и не занимают экран стенограммы. */
@Composable
fun ResultFileRoute(route: Route.ResultFile, controller: StenoController, actions: AppActions) {
    val job by remember(route.jobId) { controller.job(route.jobId) }.collectAsStateWithLifecycle()
    val title = stringResource(R.string.scr04_panel_title)
    val output = job?.result?.output
    if (output == null) {
        StenoTopBar(title, onBack = actions.back)
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val colors = StenoTheme.colors
    val copied = stringResource(R.string.scr04_path_copied)
    val noFolder = stringResource(R.string.scr04_folder_unavailable)
    val noShare = stringResource(R.string.scr04_share_unavailable)
    val shareTitle = stringResource(R.string.scr04_action_share)
    val pathLabel = stringResource(R.string.scr04_panel_path)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            StenoTopBar(title, onBack = actions.back)
            CenteredContent(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Column(
                    modifier = Modifier
                        .testTag(Tags.EXPORT_PANEL)
                        .padding(horizontal = LocalWindowLayout.current.gutter, vertical = StenoSpace.m),
                    verticalArrangement = Arrangement.spacedBy(StenoSpace.m),
                ) {
                    FileHead(output)
                    if (output.exportFailed) ExportFailure()
                    Column(Modifier.testTag(Tags.OUTPUT_META).semantics(mergeDescendants = true) { }) {
                        FactLine(stringResource(R.string.scr04_panel_size), formatFileSize(output.sizeBytes))
                        FactLine(stringResource(R.string.scr04_panel_created), formatHistoryDate(output.createdAtMs))
                        FactLine(
                            stringResource(R.string.scr04_file_contents),
                            stringResource(R.string.scr04_file_contents_value),
                        )
                    }
                    FileSection(stringResource(R.string.scr04_file_location)) {
                        Text(
                            text = output.path,
                            modifier = Modifier.testTag(Tags.OUTPUT_PATH),
                            style = StenoTheme.type.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        OutlinedAction(
                            text = stringResource(R.string.scr04_action_copy_path),
                            onClick = {
                                copyText(context, pathLabel, output.path)
                                scope.launch { snackbar.showSnackbar(copied) }
                            },
                            icon = R.drawable.ic_content_copy,
                            tag = Tags.COPY_PATH,
                        )
                        GhostAction(
                            text = stringResource(R.string.scr04_action_open_folder),
                            onClick = {
                                if (!openFolder(context, output)) scope.launch { snackbar.showSnackbar(noFolder) }
                            },
                            icon = R.drawable.ic_folder_open,
                            enabled = !output.inAppFolder,
                            tag = Tags.OPEN_FOLDER,
                        )
                    }
                    output.sha256?.let { sha ->
                        FileSection(stringResource(R.string.scr04_file_checksum)) {
                            Text(
                                text = stringResource(R.string.scr04_panel_sha),
                                style = StenoTheme.type.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                            Text(
                                text = sha,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(colors.surfaceContainer, RoundedCornerShape(StenoRadius.field))
                                    .padding(StenoSpace.m)
                                    .testTag(Tags.JSON_SHA256),
                                style = StenoTheme.type.monoSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            BottomActions {
                PrimaryAction(
                    text = shareTitle,
                    onClick = {
                        scope.launch {
                            if (!shareOutput(context, output, shareTitle)) snackbar.showSnackbar(noShare)
                        }
                    },
                    icon = R.drawable.ic_share,
                    tag = Tags.SHARE,
                )
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
