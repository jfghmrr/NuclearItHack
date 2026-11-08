package ru.stenokot.app.ui.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import ru.stenokot.app.R
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.SpeakerBadge
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.toneColors
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private val SketchWidth = 320.dp
private const val PLAYED_SHARE = 0.38f

/**
 * Упрощённая картинка экрана к странице знакомства. Только токены темы и значки приложения, поэтому
 * она следует за светлой и тёмной темой. TalkBack её пропускает: всё сказано в тексте страницы.
 */
@Composable
internal fun IntroSketch(page: IntroPage) {
    Box(Modifier.widthIn(max = SketchWidth).fillMaxWidth().clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        when (page) {
            IntroPage.IMPORT -> ChipLine(
                listOf(
                    Chip(R.drawable.ic_upload_file, stringResource(R.string.common_btn_import), BannerTone.WORKING),
                    Chip(R.drawable.ic_mic, stringResource(R.string.common_btn_record), BannerTone.NEUTRAL),
                ),
            )
            IntroPage.DEVICE -> ChipLine(
                listOf(
                    Chip(R.drawable.ic_wifi_off, stringResource(R.string.onb_device_chip_offline), BannerTone.SUCCESS),
                    Chip(R.drawable.ic_lock, stringResource(R.string.onb_device_chip_local), BannerTone.SUCCESS),
                ),
            )
            IntroPage.RESULT -> TranscriptSketch()
            IntroPage.EXPORT -> FileSketch()
        }
    }
}

private class Chip(@DrawableRes val icon: Int, val text: String, val tone: BannerTone)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipLine(chips: List<Chip>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        chips.forEach { chip ->
            val palette = toneColors(chip.tone)
            Row(
                modifier = Modifier
                    .background(palette.container, RoundedCornerShape(StenoRadius.button))
                    .padding(horizontal = StenoSpace.m, vertical = StenoSpace.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
            ) {
                StenoIcon(chip.icon, palette.icon, size = StenoSize.iconMedium)
                Text(chip.text, style = StenoTheme.type.labelMedium, color = palette.content)
            }
        }
    }
}

/** Карточка-подложка для мини-макета. */
@Composable
private fun SketchCard(content: @Composable () -> Unit) {
    val colors = StenoTheme.colors
    val shape = RoundedCornerShape(StenoRadius.card)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface, shape)
            .border(StenoSize.hairline, colors.outlineVariant, shape)
            .padding(StenoSpace.m),
        verticalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) { content() }
}

@Composable
private fun TranscriptSketch() {
    val colors = StenoTheme.colors
    SketchCard {
        SketchLine(1, "00:00", stringResource(R.string.onb_result_line_1))
        SketchLine(2, "00:04", stringResource(R.string.onb_result_line_2))
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = StenoSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
        ) {
            StenoIcon(R.drawable.ic_play_arrow_fill, colors.primary, size = StenoSize.iconMedium)
            Row(Modifier.weight(1f).height(StenoSize.progressHeight).background(colors.outlineVariant, RoundedCornerShape(StenoRadius.xs))) {
                Box(
                    Modifier
                        .weight(PLAYED_SHARE)
                        .height(StenoSize.progressHeight)
                        .background(colors.primary, RoundedCornerShape(StenoRadius.xs)),
                )
                Box(Modifier.weight(1f - PLAYED_SHARE))
            }
        }
    }
}

@Composable
private fun SketchLine(speaker: Int, time: String, text: String) {
    val colors = StenoTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(StenoSpace.s), verticalAlignment = Alignment.Top) {
        SpeakerBadge(speaker)
        Column(Modifier.weight(1f)) {
            Text(time, style = StenoTheme.type.monoSmall, color = colors.onSurfaceVariant)
            Text(text, style = StenoTheme.type.bodyMedium, color = colors.onSurface)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileSketch() {
    val colors = StenoTheme.colors
    val success = toneColors(BannerTone.SUCCESS)
    SketchCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StenoSpace.m)) {
            Box(
                Modifier.background(colors.primaryContainer, RoundedCornerShape(StenoRadius.tile)).padding(StenoSpace.s),
            ) { StenoIcon(R.drawable.ic_description, colors.primary, size = StenoSize.icon) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.onb_export_file), style = StenoTheme.type.cardTitle, color = colors.onSurface)
                Text(stringResource(R.string.onb_export_folder), style = StenoTheme.type.monoSmall, color = colors.onSurfaceVariant)
            }
            StenoIcon(R.drawable.ic_check_circle, success.icon, size = StenoSize.icon)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
            verticalArrangement = Arrangement.spacedBy(StenoSpace.xs),
        ) {
            MiniAction(R.drawable.ic_share, stringResource(R.string.scr04_action_share), colors.primary)
            MiniAction(R.drawable.ic_folder_open, stringResource(R.string.scr04_action_open_folder), colors.primary)
        }
    }
}

@Composable
private fun MiniAction(@DrawableRes icon: Int, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StenoSpace.xs)) {
        StenoIcon(icon, tint, size = StenoSize.iconSmall)
        Text(text, style = StenoTheme.type.bodySmall, color = tint)
    }
}
