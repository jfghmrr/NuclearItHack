package ru.stenokot.app.ui.result

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import ru.stenokot.app.R
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.QuietAction
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private const val MS_IN_SECOND = 1_000L
private const val NARROW_WIDTH_DP = 380
private val CardPadding = 14.dp

/**
 * Позиция слева и длительность справа. Для автоматики это по-прежнему одна строка с обоими значениями.
 * Подписано только на смену секунды, остальной экран об этом не знает.
 */
@Composable
private fun PlayerTime(player: ResultPlayer, durationMs: Long, modifier: Modifier = Modifier) {
    val seconds by remember(player) { derivedStateOf { player.positionMs / MS_IN_SECOND } }
    val position = formatTimecode(seconds * MS_IN_SECOND)
    val duration = formatTimecode(durationMs)
    val whole = stringResource(R.string.scr04_player_time, position, duration)
    val style = StenoTheme.type.monoSmall
    val color = StenoTheme.colors.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(Tags.PLAYER_TIME)
            .clearAndSetSemantics { text = AnnotatedString(whole) },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(position, style = style, color = color, maxLines = 1, softWrap = false)
        Text(duration, style = style, color = color, maxLines = 1, softWrap = false)
    }
}

/**
 * Компактный плеер: круглая кнопка, огибающая с говорящими под ней и время. Перемотка работает и без
 * звука: лента остаётся навигацией по записи. [onSeeked] сообщает о завершённой перемотке, чтобы
 * список перешёл к этому месту.
 */
@Composable
internal fun PlayerRow(
    player: ResultPlayer,
    input: TimelineInput,
    envelope: ByteArray?,
    selectedSpeakers: Set<Int>,
    searchTimesMs: LongArray,
    onSeeked: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = StenoTheme.colors
    val window = LocalWindowLayout.current
    val shape = RoundedCornerShape(StenoRadius.card)
    val playing = player.isPlaying
    val available = player.available
    val edge = if (window.widthDp < NARROW_WIDTH_DP) StenoSpace.s else StenoSpace.l
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = edge)
            .background(colors.surface, shape)
            .border(StenoSize.hairline, colors.outlineVariant, shape)
            .padding(start = CardPadding, end = CardPadding, top = StenoSpace.s, bottom = StenoSpace.m),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CardPadding),
        ) {
            FilledIconButton(
                onClick = player::toggle,
                enabled = available,
                modifier = Modifier.size(StenoSize.touchTarget).testTag(Tags.PLAY),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                    disabledContainerColor = colors.surfaceVariant,
                    disabledContentColor = colors.outline,
                ),
            ) {
                Icon(
                    painter = painterResource(if (playing) R.drawable.ic_pause_fill else R.drawable.ic_play_arrow_fill),
                    contentDescription = stringResource(if (playing) R.string.common_pause else R.string.common_play),
                )
            }
            Column(Modifier.weight(1f)) {
                Timeline(
                    input = input,
                    envelope = envelope,
                    selectedSpeakers = selectedSpeakers,
                    searchTimesMs = searchTimesMs,
                    position = { player.positionMs },
                    onPreview = player::previewAt,
                    onCommit = {
                        player.commitPreview()
                        onSeeked(player.positionMs)
                    },
                    onSeek = { target ->
                        player.seekTo(target, play = player.isPlaying)
                        onSeeked(target)
                    },
                    description = stringResource(R.string.scr04_timeline_spoken),
                )
                PlayerTime(player, input.durationMs)
            }
        }
        if (!available) {
            Text(
                text = stringResource(
                    if (player.failed) R.string.scr04_player_failed else R.string.scr04_player_no_audio,
                ),
                modifier = Modifier.padding(top = StenoSpace.xs).testTag(Tags.PLAYER_HINT),
                style = StenoTheme.type.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** Строка под плеером: подсказка слева и импорт следующего файла в одно нажатие справа. */
@Composable
internal fun PlayerFooter(hint: String?, onImport: () -> Unit, modifier: Modifier = Modifier) {
    val gutter = LocalWindowLayout.current.gutter
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.touchTarget)
            .padding(start = gutter, end = gutter - StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hint != null) {
            Text(
                text = hint,
                modifier = Modifier.weight(1f),
                style = StenoTheme.type.bodySmall,
                color = StenoTheme.colors.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        QuietAction(stringResource(R.string.common_btn_import), onImport, tag = Tags.IMPORT)
    }
}
