package ru.stenokot.app.ui.verification

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.stenokot.app.R
import ru.stenokot.app.domain.ModelCheckUi
import ru.stenokot.app.domain.ModelItemUi
import ru.stenokot.app.domain.ModelReadiness
import ru.stenokot.app.domain.ModelsUi
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.GroupLabel
import ru.stenokot.app.ui.components.ListBlock
import ru.stenokot.app.ui.components.OutlinedAction
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.QuietAction
import ru.stenokot.app.ui.components.SectionHeader
import ru.stenokot.app.ui.components.StatusBanner
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.StenoProgressBar
import ru.stenokot.app.ui.components.StenoTopBar
import ru.stenokot.app.ui.components.spokenAs
import ru.stenokot.app.ui.format.formatMib
import ru.stenokot.app.ui.format.formatMibValue
import ru.stenokot.app.ui.licenses.LicenseCatalog
import ru.stenokot.app.ui.nav.Route
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private const val META_DOT = " · "

private fun ModelCheckUi.fraction(): Float =
    if (totalBytes > 0) (checkedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

/** Модели на устройстве. На первом месте задача и готовность, хэш живёт в карточке модели. */
@Composable
fun ModelsRoute(controller: StenoController, actions: AppActions) {
    val home by controller.home.collectAsStateWithLifecycle()
    val verification by controller.verification.collectAsStateWithLifecycle()
    val check by controller.modelCheck.collectAsStateWithLifecycle()
    val models = home.models
    val items = verification.models.ifEmpty { models.items }
    ServicePage(
        title = stringResource(R.string.settings_models),
        onBack = actions.back,
        dock = {
            if (check.running) {
                Text(
                    text = stringResource(
                        R.string.models_check_running,
                        formatMibValue(check.checkedBytes),
                        formatMibValue(check.totalBytes),
                    ),
                    modifier = Modifier.padding(bottom = StenoSpace.xs),
                    style = StenoTheme.type.bodySmall,
                    color = StenoTheme.colors.onSurfaceVariant,
                )
                StenoProgressBar(check.fraction(), Modifier.padding(bottom = StenoSpace.m))
            }
            PrimaryAction(
                text = stringResource(R.string.models_check),
                onClick = controller::checkModels,
                icon = R.drawable.ic_replay,
                enabled = !check.running,
                tag = Tags.CHECK_MODELS,
            )
        },
    ) {
        ReadinessBanner(models)
        CheckBanner(check, items)
        Column {
            SectionHeader(stringResource(R.string.models_installed), count = items.size)
            ListBlock(
                modifier = Modifier.testTag(Tags.LIST_MODELS),
                rows = items.mapIndexed { index, item ->
                    @Composable {
                        ModelRow(index, item, models, check) { actions.navigator.push(Route.ModelCard(item.name)) }
                    }
                },
            )
        }
        Text(
            text = stringResource(R.string.models_note),
            style = StenoTheme.type.bodySmall,
            color = StenoTheme.colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadinessBanner(models: ModelsUi) {
    val text = when (models.readiness) {
        ModelReadiness.READY -> stringResource(R.string.models_all_ready)
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
    val tone = when (models.readiness) {
        ModelReadiness.READY -> BannerTone.SUCCESS
        ModelReadiness.COPYING -> BannerTone.WORKING
        else -> BannerTone.WARNING
    }
    StatusBanner(
        tone = tone,
        icon = if (models.readiness == ModelReadiness.READY) R.drawable.ic_check_circle else R.drawable.ic_info,
        body = text,
        bodyTag = Tags.MODELS,
        polite = true,
    )
}

/** Итог сверки. Плашки нет, пока сверку не запускали: обещать проверенное нельзя. */
@Composable
private fun CheckBanner(check: ModelCheckUi, items: List<ModelItemUi>) {
    if (check.running || check.verdicts.isEmpty()) return
    val failed = items.filter { check.verdicts[it.name] == false }
    if (failed.isEmpty()) {
        StatusBanner(
            tone = BannerTone.SUCCESS,
            icon = R.drawable.ic_shield,
            body = stringResource(R.string.models_check_passed),
            polite = true,
        )
    } else {
        StatusBanner(
            tone = BannerTone.ERROR,
            icon = R.drawable.ic_error,
            body = stringResource(R.string.models_check_failed, failed.joinToString(", ") { it.role }),
            polite = true,
        )
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
private fun statusIcon(rowState: ModelRowState, integrity: ModelIntegrity): Int = when {
    integrity == ModelIntegrity.FAILED -> R.drawable.ic_error
    rowState == ModelRowState.READY -> R.drawable.ic_check_circle
    rowState == ModelRowState.COPYING -> R.drawable.ic_hourglass_top
    else -> R.drawable.ic_warning
}

@Composable
private fun statusTint(rowState: ModelRowState, integrity: ModelIntegrity): Color {
    val colors = StenoTheme.colors
    return when {
        integrity == ModelIntegrity.FAILED -> colors.error
        rowState == ModelRowState.READY -> colors.success
        rowState == ModelRowState.COPYING -> colors.primary
        else -> colors.warning
    }
}

@Composable
private fun ModelRow(index: Int, item: ModelItemUi, models: ModelsUi, check: ModelCheckUi, onOpen: () -> Unit) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val rowState = modelRowState(item, models.readiness)
    val integrity = modelIntegrity(item.name, check)
    val status = if (integrity == ModelIntegrity.FAILED) {
        stringResource(R.string.model_integrity_failed)
    } else {
        modelStatus(rowState, models)
    }
    val title = modelTitle(item.name)
    val size = formatMib(item.sizeBytes)
    val tint = statusTint(rowState, integrity)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.touchTarget + StenoSpace.xl)
            .testTag(Tags.rowModel(index))
            .clickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.model_open),
                onClick = onOpen,
            )
            .spokenAs(stringResource(R.string.scr08_model_spoken, item.role, item.name, size, status), item.name)
            .padding(vertical = StenoSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StenoSpace.xs / 2)) {
            Text(item.role, style = type.cardTitle, color = colors.onSurface)
            Text(
                text = listOfNotNull(title.name, title.format, size).joinToString(META_DOT),
                style = type.bodySmall,
                color = colors.onSurfaceVariant,
            )
            if (rowState != ModelRowState.READY || integrity == ModelIntegrity.FAILED) {
                Text(status, style = type.bodySmall, color = tint)
            }
            if (rowState == ModelRowState.COPYING && models.totalBytes > 0) {
                StenoProgressBar(
                    fraction = models.copiedBytes.toFloat() / models.totalBytes,
                    modifier = Modifier.padding(top = StenoSpace.xs),
                )
            }
        }
        StenoIcon(statusIcon(rowState, integrity), tint, size = StenoSize.iconMedium)
        StenoIcon(R.drawable.ic_chevron_right, colors.onSurfaceVariant)
    }
}

/** Сведения о модели. Хэш показан целиком и выделяется для копирования. */
@Composable
fun ModelCardRoute(name: String, controller: StenoController, actions: AppActions) {
    val home by controller.home.collectAsStateWithLifecycle()
    val verification by controller.verification.collectAsStateWithLifecycle()
    val check by controller.modelCheck.collectAsStateWithLifecycle()
    val models = home.models
    val title = stringResource(R.string.model_title)
    val item = verification.models.ifEmpty { models.items }.firstOrNull { it.name == name }
    if (item == null) {
        Column(Modifier.fillMaxSize()) { StenoTopBar(title, onBack = actions.back) }
        return
    }
    val license = LicenseCatalog.forModel(item.name)
    ServicePage(
        title = title,
        onBack = actions.back,
        dock = license?.let { entry ->
            @Composable {
                OutlinedAction(
                    text = stringResource(R.string.model_open_license),
                    onClick = { actions.navigator.push(Route.LicenseText(entry.id)) },
                    icon = R.drawable.ic_book,
                    tag = Tags.MODEL_LICENSE,
                )
            }
        },
    ) {
        ModelHead(item, models)
        ModelFacts(item)
        ModelIntegrityBlock(item, check, controller::checkModels)
    }
}

@Composable
private fun ModelHead(item: ModelItemUi, models: ModelsUi) {
    val rowState = modelRowState(item, models.readiness)
    val ready = rowState == ModelRowState.READY
    Column(verticalArrangement = Arrangement.spacedBy(StenoSpace.xs)) {
        LeadTile { StenoIcon(R.drawable.ic_memory, StenoTheme.colors.primary, size = StenoSize.iconLarge) }
        GroupLabel(item.role, Modifier.padding(top = StenoSpace.s))
        Text(
            text = modelTitle(item.name).name,
            modifier = Modifier.semantics { heading() },
            style = StenoTheme.type.headline,
            color = StenoTheme.colors.onSurface,
        )
        StateChip(
            text = if (ready) stringResource(R.string.model_ready) else modelStatus(rowState, models),
            icon = if (ready) R.drawable.ic_check_circle else R.drawable.ic_warning,
            tone = if (ready) BannerTone.SUCCESS else BannerTone.WARNING,
            modifier = Modifier.padding(top = StenoSpace.s),
        )
    }
}

@Composable
private fun ModelFacts(item: ModelItemUi) {
    Column {
        FactRow(stringResource(R.string.model_purpose), item.role)
        FactRow(stringResource(R.string.model_size), formatMib(item.sizeBytes))
        modelTitle(item.name).format?.let { FactRow(stringResource(R.string.model_format), it) }
        FactRow(stringResource(R.string.model_license), item.license)
    }
}

@Composable
private fun IntegrityChip(integrity: ModelIntegrity) {
    val look = when (integrity) {
        ModelIntegrity.NOT_CHECKED -> Triple(R.string.model_integrity_none, R.drawable.ic_shield, BannerTone.NEUTRAL)
        ModelIntegrity.CHECKING -> Triple(R.string.model_integrity_running, R.drawable.ic_hourglass_top, BannerTone.WORKING)
        ModelIntegrity.PASSED -> Triple(R.string.model_integrity_passed, R.drawable.ic_shield, BannerTone.SUCCESS)
        ModelIntegrity.FAILED -> Triple(R.string.model_integrity_failed, R.drawable.ic_error, BannerTone.ERROR)
    }
    StateChip(stringResource(look.first), look.second, look.third, tag = Tags.MODEL_INTEGRITY)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelIntegrityBlock(item: ModelItemUi, check: ModelCheckUi, onCheck: () -> Unit) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val integrity = modelIntegrity(item.name, check)
    Column(verticalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
        SectionHeader(stringResource(R.string.model_integrity))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            IntegrityChip(integrity)
            if (integrity != ModelIntegrity.CHECKING) {
                QuietAction(stringResource(R.string.model_check), onCheck, tag = Tags.CHECK_MODELS)
            }
        }
        item.sha256?.let { hash ->
            Text(
                text = stringResource(R.string.scr08_json_sha),
                modifier = Modifier.padding(top = StenoSpace.xs),
                style = type.bodySmall,
                color = colors.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    text = hash,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceContainer, RoundedCornerShape(StenoRadius.field))
                        .padding(horizontal = StenoSpace.l, vertical = StenoSpace.m)
                        .testTag(Tags.MODEL_SHA256),
                    style = type.monoSmall.copy(lineHeight = type.bodySmall.lineHeight),
                    color = colors.onSurface,
                )
            }
            Text(stringResource(R.string.model_sha_note), style = type.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}
