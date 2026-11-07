package ru.stenokot.app.ui.verification

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.stenokot.app.R
import ru.stenokot.app.domain.ModelItemUi
import ru.stenokot.app.domain.ModelReadiness
import ru.stenokot.app.domain.ModelsUi
import ru.stenokot.app.domain.StageMetricUi
import ru.stenokot.app.domain.VerificationUi
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.ActionDock
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.DiagnosticsCopy
import ru.stenokot.app.ui.components.LinkRow
import ru.stenokot.app.ui.components.OutlinedAction
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.SectionHeader
import ru.stenokot.app.ui.components.StatusBanner
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.StenoProgressBar
import ru.stenokot.app.ui.components.StenoTopBar
import ru.stenokot.app.ui.components.spokenAs
import ru.stenokot.app.ui.components.tagged
import ru.stenokot.app.ui.components.toneColors
import ru.stenokot.app.ui.format.formatGib
import ru.stenokot.app.ui.format.formatMib
import ru.stenokot.app.ui.format.formatMibValue
import ru.stenokot.app.ui.format.formatRatio
import ru.stenokot.app.ui.format.formatThousands
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.app.ui.format.stageLabel
import ru.stenokot.app.ui.layout.CenteredContent
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoMotion
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.core.model.Stage

private const val NUMERAL_WIDTH_EM = 2.6f
private const val CHEVRON_OPEN = 90f

/** Всё, что нужно экрану для отрисовки. */
@Immutable
internal data class VerificationScreenState(
    val verification: VerificationUi,
    val models: ModelsUi,
    val items: List<ModelItemUi>,
    val facts: PackageFacts?,
    val canExport: Boolean,
    val exportFailed: Boolean,
    val notificationsAllowed: Boolean,
)

@Composable
internal fun VerificationScreen(
    state: VerificationScreenState,
    copy: DiagnosticsCopy,
    callbacks: VerificationCallbacks,
) {
    val layout = LocalWindowLayout.current
    val colors = StenoTheme.colors
    val title = stringResource(R.string.common_btn_models)
    val copyAction: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(StenoSpace.xs)) {
            PrimaryAction(
                text = stringResource(if (copy.copied) R.string.scr08_copied_package else R.string.scr08_copy_package),
                onClick = copy::copy,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                icon = R.drawable.ic_content_copy,
                tag = Tags.COPY_DIAGNOSTICS,
            )
            Text(
                text = stringResource(R.string.scr08_package_hint),
                modifier = Modifier.padding(horizontal = StenoSpace.xs),
                style = StenoTheme.type.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
    val dock: @Composable () -> Unit = {
        ActionDock { Column(Modifier.padding(horizontal = layout.gutter, vertical = StenoSpace.m)) { copyAction() } }
    }
    if (layout.twoPane) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(0.46f).fillMaxHeight()) {
                StenoTopBar(title, onBack = callbacks.onBack)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = layout.gutter)
                        .padding(bottom = StenoSpace.l),
                    verticalArrangement = Arrangement.spacedBy(StenoSpace.l),
                ) {
                    OfflineProof(state)
                    ModelsSection(state)
                    LinksSection(state, callbacks)
                    if (layout.short) copyAction()
                }
                if (!layout.short) dock()
            }
            Spacer(Modifier.width(StenoSize.hairline).fillMaxHeight().background(colors.outlineVariant))
            Column(
                modifier = Modifier
                    .weight(0.54f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = layout.gutter, vertical = StenoSpace.s),
                verticalArrangement = Arrangement.spacedBy(StenoSpace.m),
            ) {
                MetricsSection(state, callbacks)
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            StenoTopBar(title, onBack = callbacks.onBack)
            CenteredContent(Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = layout.gutter)
                        .padding(bottom = StenoSpace.l),
                    verticalArrangement = Arrangement.spacedBy(StenoSpace.l),
                ) {
                    OfflineProof(state)
                    ModelsSection(state)
                    Column(verticalArrangement = Arrangement.spacedBy(StenoSpace.m)) {
                        MetricsSection(state, callbacks)
                    }
                    LinksSection(state, callbacks)
                    if (layout.tiny) copyAction()
                }
            }
            if (!layout.tiny) dock()
        }
    }
}

/** Первое, что видит проверяющий: вердикт и строки, которыми он подтверждён. */
@Composable
private fun OfflineProof(state: VerificationScreenState) {
    val type = StenoTheme.type
    val facts = state.facts
    val verification = state.verification
    val internetAbsent = verification.internetPermissionAbsent && facts?.internetAbsent != false
    val networkAbsent = verification.networkStatePermissionAbsent && facts?.networkStateAbsent != false
    val clean = internetAbsent && networkAbsent
    val palette = toneColors(if (clean) BannerTone.SUCCESS else BannerTone.ERROR)
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.container, RoundedCornerShape(StenoRadius.xl))
            .padding(StenoSpace.l),
        verticalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(StenoSpace.m)) {
            StenoIcon(if (clean) R.drawable.ic_wifi_off else R.drawable.ic_error, palette.icon, size = StenoSize.icon)
            Text(
                text = stringResource(if (clean) R.string.scr08_offline else R.string.scr08_online_found),
                modifier = Modifier.weight(1f).testTag(Tags.OFFLINE),
                style = type.labelLarge,
                color = palette.content,
            )
        }
        PermissionLine("INTERNET", internetAbsent, palette.content)
        PermissionLine("ACCESS_NETWORK_STATE", networkAbsent, palette.content)
        facts?.let {
            val version = verification.appVersion.ifEmpty { it.versionName }
            Text(
                text = stringResource(R.string.scr08_package_checked, it.packageName, version),
                style = type.bodySmall,
                color = palette.content,
            )
            QuietExpander(
                title = stringResource(if (open) R.string.scr08_permissions_hide else R.string.scr08_permissions_all),
                count = it.permissions.size,
                open = open,
                tint = palette.content,
                onClick = { open = !open },
            )
            Reveal(open) {
                Column(verticalArrangement = Arrangement.spacedBy(StenoSpace.xs)) {
                    it.permissions.forEach { name ->
                        Text(shortPermission(name), style = type.monoSmall, color = palette.content)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionLine(name: String, absent: Boolean, tint: Color) {
    val type = StenoTheme.type
    FlowRow(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, style = type.monoSmall, color = tint)
        Text(
            text = stringResource(if (absent) R.string.scr08_permission_absent else R.string.scr08_permission_present),
            style = type.bodySmall,
            color = tint,
        )
    }
}

/** Плавное раскрытие по нажатию. При отключённой анимации система делает его мгновенным. */
@Composable
private fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(StenoMotion.MEDIUM)) + expandVertically(tween(StenoMotion.MEDIUM)),
        exit = fadeOut(tween(StenoMotion.SHORT)) + shrinkVertically(tween(StenoMotion.SHORT)),
    ) { content() }
}

@Composable
private fun QuietExpander(title: String, count: Int?, open: Boolean, tint: Color, onClick: () -> Unit) {
    val type = StenoTheme.type
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.touchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(Tags.PERMISSIONS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        Text(title, Modifier.weight(1f), style = type.labelMedium, color = tint)
        count?.let { Text(it.toString(), style = type.monoMedium, color = tint) }
        Chevron(open, tint)
    }
}

@Composable
private fun Chevron(open: Boolean, tint: Color) {
    val angle by animateFloatAsState(if (open) CHEVRON_OPEN else 0f, tween(StenoMotion.MEDIUM), label = "chevron")
    StenoIcon(R.drawable.ic_chevron_right, tint, Modifier.rotate(angle))
}

@Composable
private fun ModelsSummary(models: ModelsUi) {
    val text = when (models.readiness) {
        ModelReadiness.READY -> stringResource(R.string.scr01_models_ready)
        ModelReadiness.COPYING -> stringResource(
            R.string.scr01_models_copying,
            formatMibValue(models.copiedBytes),
            formatMibValue(models.totalBytes),
        )
        ModelReadiness.LLM_UNAVAILABLE -> stringResource(R.string.scr01_models_llm_unavailable)
        ModelReadiness.ASR_UNAVAILABLE -> stringResource(R.string.scr01_models_asr_unavailable)
        ModelReadiness.STORAGE_LOW -> stringResource(
            R.string.scr01_models_storage_low,
            formatMibValue((models.totalBytes - models.freeBytes).coerceAtLeast(0)),
        )
    }
    Text(
        text = text,
        modifier = Modifier
            .testTag(Tags.MODELS)
            .semantics { liveRegion = LiveRegionMode.Polite },
        style = StenoTheme.type.bodyMedium,
        color = StenoTheme.colors.onSurfaceVariant,
    )
}

@Composable
private fun ModelsSection(state: VerificationScreenState) {
    Column {
        SectionHeader(stringResource(R.string.scr08_section_models), count = state.items.size)
        ModelsSummary(state.models)
        Column(Modifier.padding(top = StenoSpace.xs).testTag(Tags.LIST_MODELS)) {
            state.items.forEachIndexed { index, item -> ModelRow(index, item, state.models) }
        }
    }
}

@Composable
private fun modelStatus(rowState: ModelRowState, models: ModelsUi): String = when (rowState) {
    ModelRowState.READY -> stringResource(R.string.scr08_model_status_ready)
    ModelRowState.COPYING -> stringResource(
        R.string.scr08_model_copying,
        formatMibValue(models.copiedBytes),
        formatMibValue(models.totalBytes),
    )
    ModelRowState.UNAVAILABLE -> stringResource(R.string.scr08_model_unavailable)
    ModelRowState.ASR_UNAVAILABLE -> stringResource(R.string.scr08_model_asr_unavailable)
    ModelRowState.STORAGE_LOW -> stringResource(
        R.string.scr08_model_storage_low,
        formatMibValue((models.totalBytes - models.freeBytes).coerceAtLeast(0)),
    )
}

@DrawableRes
private fun modelIcon(rowState: ModelRowState): Int = when (rowState) {
    ModelRowState.READY -> R.drawable.ic_check_circle_fill
    ModelRowState.COPYING -> R.drawable.ic_hourglass_top
    else -> R.drawable.ic_warning
}

@Composable
private fun modelIconTint(rowState: ModelRowState): Color {
    val colors = StenoTheme.colors
    return when (rowState) {
        ModelRowState.READY -> colors.success
        ModelRowState.COPYING -> colors.primary
        else -> colors.warning
    }
}

@Composable
private fun ModelRow(index: Int, item: ModelItemUi, models: ModelsUi) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val rowState = modelRowState(item, models.readiness)
    val status = modelStatus(rowState, models)
    val size = formatMib(item.sizeBytes)
    var open by rememberSaveable(item.name) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = StenoSize.touchTarget + StenoSpace.l)
                .testTag(Tags.rowModel(index))
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.scr08_model_open),
                    onClick = { open = !open },
                )
                .spokenAs(stringResource(R.string.scr08_model_spoken, item.name, item.role, size, status), item.name)
                .padding(vertical = StenoSpace.s),
            horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
        ) {
            StenoIcon(modelIcon(rowState), modelIconTint(rowState), size = StenoSize.icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StenoSpace.xs / 2)) {
                Text(
                    text = item.name,
                    style = type.labelMedium,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.scr08_model_ready, item.role, size),
                    style = type.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                if (rowState == ModelRowState.READY) {
                    item.sha256?.let {
                        Text(
                            text = stringResource(R.string.scr08_model_hash, hashPrefix(it)),
                            style = type.monoSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                } else {
                    Text(status, style = type.bodySmall, color = modelIconTint(rowState))
                }
                if (rowState == ModelRowState.COPYING && models.totalBytes > 0) {
                    StenoProgressBar(
                        fraction = models.copiedBytes.toFloat() / models.totalBytes,
                        modifier = Modifier.padding(top = StenoSpace.xs),
                    )
                }
            }
            Chevron(open, colors.onSurfaceVariant)
        }
        Reveal(open) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = StenoSize.icon + StenoSpace.m, bottom = StenoSpace.s),
                verticalArrangement = Arrangement.spacedBy(StenoSpace.xs),
            ) {
                Text(
                    text = stringResource(R.string.scr08_model_hash, item.sha256 ?: stringResource(R.string.scr08_not_measured)),
                    style = type.monoSmall,
                    color = colors.onSurface,
                )
                Text(stringResource(R.string.scr08_model_license, item.license), style = type.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MetricsSection(state: VerificationScreenState, callbacks: VerificationCallbacks) {
    val verification = state.verification
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val hasRun = verification.lastJobTitle != null
    Column {
        SectionHeader(stringResource(R.string.scr08_section_metrics))
        Text(
            text = verification.lastJobTitle ?: stringResource(R.string.scr08_no_metrics),
            style = type.bodyMedium,
            color = colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
    RtfPair(verification.rtfCore, verification.rtfFull)
    if (!hasRun) return
    RunFacts(state)
    StageTable(verification.stageMetrics)
    JsonFacts(verification)
    if (state.exportFailed) {
        StatusBanner(
            tone = BannerTone.ERROR,
            icon = R.drawable.ic_error,
            body = stringResource(R.string.scr08_export_failed),
            tag = Tags.EXPORT_ERROR,
            polite = true,
        )
    }
    if (state.canExport) {
        OutlinedAction(
            text = stringResource(R.string.common_btn_export_meta),
            onClick = callbacks.onExportMeta,
            icon = R.drawable.ic_download,
            tag = Tags.EXPORT_META,
        )
    }
}

/** Пара главных замеров. Названия всегда полные: слово RTF без уточнения в интерфейсе запрещено. */
@Composable
private fun RtfPair(core: Double?, full: Double?) {
    val numeral = StenoTheme.type.numeral
    val coreCell: @Composable (Modifier) -> Unit = { modifier ->
        RtfCell(
            name = "RTF_core",
            line = R.string.scr08_rtf_core,
            hint = stringResource(R.string.scr08_rtf_core_hint),
            value = core,
            tag = Tags.RTF_CORE,
            modifier = modifier,
        )
    }
    val fullCell: @Composable (Modifier) -> Unit = { modifier ->
        RtfCell(
            name = "RTF_full",
            line = R.string.scr08_rtf_full,
            hint = stringResource(R.string.scr08_rtf_full_hint),
            value = full,
            tag = Tags.RTF_FULL,
            modifier = modifier,
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val needed = with(LocalDensity.current) { numeral.fontSize.toDp() } * NUMERAL_WIDTH_EM
        if (maxWidth / 2 < needed + StenoSpace.m) {
            Column(verticalArrangement = Arrangement.spacedBy(StenoSpace.m)) {
                coreCell(Modifier.fillMaxWidth())
                fullCell(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(StenoSpace.l)) {
                coreCell(Modifier.weight(1f))
                fullCell(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RtfCell(
    name: String,
    @StringRes line: Int,
    hint: String,
    value: Double?,
    tag: String,
    modifier: Modifier,
) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val shown = value?.let(::formatRatio)
    val text = stringResource(line, shown ?: stringResource(R.string.scr08_not_measured))
    Column(
        modifier = modifier
            .testTag(tag)
            .spokenAs("$text. $hint", text),
        verticalArrangement = Arrangement.spacedBy(StenoSpace.xs / 2),
    ) {
        Text(name, style = type.monoMedium, color = colors.onSurface)
        if (shown != null) {
            Text(shown, style = type.numeral, color = colors.onSurface, maxLines = 1, softWrap = false)
        } else {
            Text(stringResource(R.string.scr08_not_measured), style = type.titleMedium, color = colors.onSurfaceVariant)
        }
        Text(hint, style = type.bodySmall, color = colors.onSurfaceVariant)
    }
}

/** Строка таблицы: подпись слева, значение справа, длинное значение уходит на новую строку. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FactRow(label: String, value: String, tag: String? = null) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val line = colors.outlineVariant
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .drawBehind {
                drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), StenoSize.hairline.toPx())
            }
            .tagged(tag)
            .semantics(mergeDescendants = true) { }
            .padding(vertical = StenoSpace.s),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(StenoSpace.xs / 2),
    ) {
        Text(label, style = type.bodyMedium, color = colors.onSurfaceVariant)
        Text(value, style = type.monoMedium, color = colors.onSurface)
    }
}

@Composable
private fun measured(value: String?): String = value ?: stringResource(R.string.scr08_not_measured)

@Composable
private fun RunFacts(state: VerificationScreenState) {
    val verification = state.verification
    val facts = state.facts
    val llm = verification.stageMetrics.firstOrNull { it.stage == Stage.LLM }
    Column {
        FactRow(stringResource(R.string.scr08_metric_audio), measured(verification.audioDurationMs?.let(::formatTimecode)))
        FactRow(stringResource(R.string.scr08_metric_processing), measured(verification.processingMs?.let(::formatTimecode)))
        FactRow(stringResource(R.string.scr08_metric_memory), measured(verification.peakRssMb?.let(::formatGib)))
        FactRow(
            stringResource(R.string.scr08_metric_device),
            measured(
                facts?.let {
                    stringResource(R.string.scr08_metric_device_value, it.deviceModel, formatGib(it.totalMemoryMib), it.apiLevel)
                },
            ),
        )
        FactRow(stringResource(R.string.scr08_metric_threads), measured(verification.threads?.toString()))
        FactRow(
            stringResource(R.string.scr08_metric_llm),
            measured(
                if (llm != null && verification.llmStatus != null) {
                    stringResource(
                        R.string.scr08_metric_llm_value,
                        formatTimecode(llm.tookMs),
                        verification.llmStatus.name.lowercase(),
                    )
                } else {
                    null
                },
            ),
        )
        FactRow(
            stringResource(R.string.scr08_metric_tokens),
            measured(verification.llmTokens?.let(::formatThousands)),
        )
    }
}

@Composable
private fun StageTable(metrics: List<StageMetricUi>) {
    val rows = metrics.filter { it.stage != Stage.LLM }
    if (rows.isEmpty()) return
    Column(Modifier.testTag(Tags.LIST_STAGE_METRICS)) {
        SectionHeader(stringResource(R.string.scr08_section_stages), count = rows.size)
        rows.forEach { FactRow(stageLabel(it.stage), formatTimecode(it.tookMs)) }
    }
}

@Composable
private fun JsonFacts(verification: VerificationUi) {
    val verdict = when (verification.jsonValid) {
        true -> stringResource(R.string.scr08_json_passed)
        false -> stringResource(R.string.scr08_json_failed)
        null -> null
    }
    Column {
        SectionHeader(stringResource(R.string.scr08_section_json))
        FactRow(stringResource(R.string.scr08_json_valid), measured(verdict), Tags.JSON_VALID)
        FactRow(stringResource(R.string.scr08_json_sha), measured(verification.jsonSha256), Tags.JSON_SHA256)
        FactRow(stringResource(R.string.scr08_json_path), measured(verification.jsonPath), Tags.JSON_PATH)
    }
}

@Composable
private fun LinksSection(state: VerificationScreenState, callbacks: VerificationCallbacks) {
    val colors = StenoTheme.colors
    Column {
        LinkRow(
            title = stringResource(R.string.scr08_notifications),
            subtitle = stringResource(
                if (state.notificationsAllowed) R.string.scr08_notifications_on else R.string.scr08_notifications_off,
            ),
            icon = R.drawable.ic_notifications,
            trailing = if (state.notificationsAllowed) R.drawable.ic_check_circle else R.drawable.ic_chevron_right,
            onClick = if (state.notificationsAllowed) null else callbacks.onNotifications,
            tag = Tags.NOTIFICATIONS,
        )
        Spacer(Modifier.fillMaxWidth().height(StenoSize.hairline).background(colors.outlineVariant))
        LinkRow(
            title = stringResource(R.string.scr08_licenses),
            subtitle = stringResource(R.string.scr08_licenses_hint),
            icon = R.drawable.ic_gavel,
            onClick = callbacks.onLicenses,
            tag = Tags.LICENSES,
        )
    }
}
