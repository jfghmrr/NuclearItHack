package ru.stenokot.app.ui.verification

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import ru.stenokot.app.R
import ru.stenokot.app.domain.ExportKind
import ru.stenokot.app.domain.JobDetailUi
import ru.stenokot.app.domain.StageMetricUi
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.domain.VerificationUi
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.DiagnosticsCopy
import ru.stenokot.app.ui.components.ListBlock
import ru.stenokot.app.ui.components.OutlinedAction
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.SectionHeader
import ru.stenokot.app.ui.components.StatusBanner
import ru.stenokot.app.ui.components.StenoTopBar
import ru.stenokot.app.ui.components.rememberDiagnosticsCopy
import ru.stenokot.app.ui.components.spokenAs
import ru.stenokot.app.ui.components.tagged
import ru.stenokot.app.ui.format.formatGib
import ru.stenokot.app.ui.format.formatRatio
import ru.stenokot.app.ui.format.formatStageTime
import ru.stenokot.app.ui.format.formatThousands
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.app.ui.format.stageLabel
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.core.model.Stage

private const val META_DOT = " · "
private const val TWO_PANE_SHARE = 0.5f

/** Всё, что нужно экрану: ни одно значение не появляется здесь без пакета или замера. */
@Immutable
private data class DiagnosticsState(
    val verification: VerificationUi,
    val facts: PackageFacts?,
    val canExport: Boolean,
    val exportFailed: Boolean,
)

/** SCR-08: замеры последней записи, этапы, среда и файл JSON. */
@Composable
fun DiagnosticsRoute(controller: StenoController, actions: AppActions) {
    val verification by controller.verification.collectAsStateWithLifecycle()
    val home by controller.home.collectAsStateWithLifecycle()
    val facts by rememberPackageFacts()
    val jobId = lastJobId(verification, home.jobs)
    val jobFlow = remember(jobId) { jobId?.let(controller::job) ?: MutableStateFlow<JobDetailUi?>(null) }
    val job by jobFlow.collectAsStateWithLifecycle()
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null && jobId != null) controller.export(jobId, ExportKind.METADATA, uri)
    }
    val copy = rememberDiagnosticsCopy(controller, null)
    val title = verification.lastJobTitle
    val onExport = remember(title) {
        { if (title != null) runCatching { exporter.launch(metadataFileName(title)) } }
    }
    DiagnosticsScreen(
        state = DiagnosticsState(
            verification = verification,
            facts = facts,
            canExport = jobId != null,
            exportFailed = job?.result?.output?.exportFailed == true,
        ),
        copy = copy,
        onBack = actions.back,
        onExport = onExport,
    )
}

@Composable
private fun DiagnosticsScreen(state: DiagnosticsState, copy: DiagnosticsCopy, onBack: () -> Unit, onExport: () -> Unit) {
    val layout = LocalWindowLayout.current
    val title = stringResource(R.string.settings_diagnostics)
    val hasRun = state.verification.lastJobTitle != null
    if (!layout.twoPane) {
        ServicePage(title = title, onBack = onBack, dock = { CopyAction(copy) }) {
            Measures(state.verification)
            if (hasRun) {
                StageTable(state.verification.stageMetrics)
                Environment(state)
                JsonFacts(state, onExport)
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        StenoTopBar(title, onBack = onBack)
        Row(Modifier.weight(1f)) {
            Pane {
                Measures(state.verification)
                if (hasRun) StageTable(state.verification.stageMetrics)
            }
            Spacer(Modifier.width(StenoSize.hairline).fillMaxHeight().background(StenoTheme.colors.outlineVariant))
            Pane {
                if (hasRun) {
                    Environment(state)
                    JsonFacts(state, onExport)
                }
                CopyAction(copy)
            }
        }
    }
}

@Composable
private fun RowScope.Pane(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .weight(TWO_PANE_SHARE)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LocalWindowLayout.current.gutter)
            .padding(bottom = StenoSpace.l),
        verticalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) { content() }
}

@Composable
private fun CopyAction(copy: DiagnosticsCopy) {
    PrimaryAction(
        text = stringResource(if (copy.copied) R.string.scr07_copied else R.string.scr07_copy),
        onClick = copy::copy,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        icon = R.drawable.ic_content_copy,
        tag = Tags.COPY_REPORT,
    )
    Text(
        text = stringResource(R.string.scr08_package_hint),
        modifier = Modifier.fillMaxWidth().padding(top = StenoSpace.xs),
        style = StenoTheme.type.bodySmall,
        color = StenoTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** Подпись записи и четыре главных числа. С крупным шрифтом плитки встают в столбик. */
@Composable
private fun Measures(verification: VerificationUi) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val title = verification.lastJobTitle
    val duration = verification.audioDurationMs?.let(::formatTimecode)
    val time = verification.processingMs?.let(::formatTimecode)
    val memory = verification.peakRssMb?.let(::formatGib)
    val core = verification.rtfCore?.let(::formatRatio)
    val full = verification.rtfFull?.let(::formatRatio)
    val notMeasured = stringResource(R.string.scr08_not_measured)
    Text(
        text = title?.let { listOfNotNull(it, duration).joinToString(META_DOT) } ?: stringResource(R.string.scr08_no_metrics),
        style = type.bodyMedium,
        color = colors.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.MiddleEllipsis,
    )
    val cells: List<@Composable (Modifier) -> Unit> = listOf(
        { MetricTile(time, stringResource(R.string.scr08_metric_processing), it) },
        { MetricTile(memory, stringResource(R.string.scr08_metric_memory), it) },
        {
            MetricTile(
                value = core,
                label = stringResource(R.string.diagnostics_rtf_core),
                modifier = it,
                tag = Tags.RTF_CORE,
                machine = stringResource(R.string.scr08_rtf_core, core ?: notMeasured),
            )
        },
        {
            MetricTile(
                value = full,
                label = stringResource(R.string.diagnostics_rtf_full),
                modifier = it,
                tag = Tags.RTF_FULL,
                machine = stringResource(R.string.scr08_rtf_full, full ?: notMeasured),
            )
        },
    )
    if (LocalWindowLayout.current.largeText) {
        Column(verticalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
            cells.forEach { it(Modifier.fillMaxWidth()) }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
            cells.chunked(2).forEach { pair ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
                    pair.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
    Text(stringResource(R.string.diagnostics_rtf_hint), style = type.bodySmall, color = colors.onSurfaceVariant)
}

/**
 * Плитка замера. [machine] это строка для автоматики и TalkBack: подпись RTF на экране сокращена,
 * а в дереве доступности остаётся полное имя показателя.
 */
@Composable
private fun MetricTile(value: String?, label: String, modifier: Modifier, tag: String? = null, machine: String? = null) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val shape = RoundedCornerShape(StenoRadius.card)
    val hasValue = value != null
    val shown = value ?: stringResource(R.string.scr08_not_measured)
    Column(
        modifier = modifier
            .background(colors.surface, shape)
            .border(StenoSize.hairline, colors.outlineVariant, shape)
            .tagged(tag)
            .then(
                if (machine != null) {
                    Modifier.spokenAs("$machine. $label", machine)
                } else {
                    Modifier.semantics(mergeDescendants = true) { }
                },
            )
            .padding(StenoSpace.l),
        verticalArrangement = Arrangement.spacedBy(StenoSpace.xs),
    ) {
        if (hasValue) {
            Text(
                text = shown,
                color = colors.onSurface,
                style = type.metric,
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = type.metric.fontSize),
            )
        } else {
            Text(shown, style = type.titleMedium, color = colors.onSurfaceVariant)
        }
        Text(label, style = type.bodySmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun StageTable(metrics: List<StageMetricUi>) {
    val rows = metrics.filter { it.stage != Stage.LLM }
    if (rows.isEmpty()) return
    val value = StenoTheme.type.monoMedium.copy(fontWeight = FontWeight.W700)
    Column {
        SectionHeader(stringResource(R.string.diagnostics_stages), count = rows.size)
        ListBlock(
            modifier = Modifier.testTag(Tags.LIST_STAGE_METRICS),
            rows = rows.map { metric ->
                @Composable { FactRow(stageLabel(metric.stage), formatStageTime(metric.tookMs), valueStyle = value, divider = false) }
            },
        )
    }
}

@Composable
private fun measured(value: String?): String = value ?: stringResource(R.string.scr08_not_measured)

@Composable
private fun Environment(state: DiagnosticsState) {
    val verification = state.verification
    val facts = state.facts
    val llm = verification.stageMetrics.firstOrNull { it.stage == Stage.LLM }
    val llmStatus = verification.llmStatus?.name?.lowercase()
    Column {
        SectionHeader(stringResource(R.string.diagnostics_environment))
        FactRow(stringResource(R.string.scr08_metric_audio), measured(verification.audioDurationMs?.let(::formatTimecode)))
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
                if (llm != null && llmStatus != null) {
                    stringResource(R.string.scr08_metric_llm_value, formatTimecode(llm.tookMs), llmStatus)
                } else {
                    llmStatus
                },
            ),
        )
        FactRow(stringResource(R.string.scr08_metric_tokens), measured(verification.llmTokens?.let(::formatThousands)))
    }
}

@Composable
private fun JsonFacts(state: DiagnosticsState, onExport: () -> Unit) {
    val verification = state.verification
    val verdict = when (verification.jsonValid) {
        true -> stringResource(R.string.scr08_json_passed)
        false -> stringResource(R.string.scr08_json_failed)
        null -> null
    }
    val mono = StenoTheme.type.monoSmall
    Column {
        SectionHeader(stringResource(R.string.scr08_section_json))
        FactRow(stringResource(R.string.scr08_json_valid), measured(verdict), Tags.JSON_VALID)
        FactRow(stringResource(R.string.scr08_json_sha), measured(verification.jsonSha256), Tags.JSON_SHA256, mono)
        FactRow(stringResource(R.string.scr08_json_path), measured(verification.jsonPath), Tags.JSON_PATH, mono)
    }
    if (state.exportFailed) {
        StatusBanner(
            tone = BannerTone.ERROR,
            icon = R.drawable.ic_error,
            body = stringResource(R.string.scr08_export_failed),
            tag = Tags.META_EXPORT_ERROR,
            polite = true,
        )
    }
    if (state.canExport) {
        OutlinedAction(
            text = stringResource(R.string.common_btn_export_meta),
            onClick = onExport,
            icon = R.drawable.ic_download,
            tag = Tags.EXPORT_META,
        )
    }
}
