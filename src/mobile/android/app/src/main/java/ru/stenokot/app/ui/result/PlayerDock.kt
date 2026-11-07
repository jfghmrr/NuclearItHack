package ru.stenokot.app.ui.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextAlign
import ru.stenokot.app.R
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.format.formatTimecode
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private const val MS_IN_SECOND = 1_000L

/** Время плеера. Подписано только на смену секунды, остальной экран об этом не знает. */
@Composable
private fun PlayerTime(player: ResultPlayer, durationMs: Long, modifier: Modifier = Modifier) {
    val seconds by remember(player) { derivedStateOf { player.positionMs / MS_IN_SECOND } }
    Text(
        text = stringResource(
            R.string.scr04_player_time,
            formatTimecode(seconds * MS_IN_SECOND),
            formatTimecode(durationMs),
        ),
        modifier = modifier.testTag(Tags.PLAYER_TIME),
        style = StenoTheme.type.monoMedium,
        color = StenoTheme.colors.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
    )
}

/**
 * Плеер: кнопка, лента говорящих с перемоткой и время. Перемотка работает и без звука: лента
 * остаётся навигацией по записи. [onSeeked] сообщает о завершённой перемотке, чтобы список
 * перешёл к этому месту.
 */
@Composable
internal fun PlayerRow(
    player: ResultPlayer,
    input: TimelineInput,
    selectedSpeakers: Set<Int>,
    searchTimesMs: LongArray,
    onSeeked: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = StenoTheme.colors
    val gutter = LocalWindowLayout.current.gutter
    val playing = player.isPlaying
    val available = player.available
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = gutter - StenoSpace.s, end = gutter, top = StenoSpace.xs),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.xs),
    ) {
        IconButton(
            onClick = player::toggle,
            enabled = available,
            modifier = Modifier.testTag(Tags.PLAY),
        ) {
            Icon(
                painter = painterResource(if (playing) R.drawable.ic_pause_fill else R.drawable.ic_play_arrow_fill),
                contentDescription = stringResource(if (playing) R.string.common_pause else R.string.common_play),
                tint = if (available) colors.primary else colors.outline,
            )
        }
        Column(Modifier.weight(1f)) {
            Timeline(
                input = input,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
            ) {
                PlayerTime(player, input.durationMs)
                if (!available) {
                    Text(
                        text = stringResource(
                            if (player.failed) R.string.scr04_player_failed else R.string.scr04_player_no_audio,
                        ),
                        modifier = Modifier.weight(1f).testTag(Tags.PLAYER_HINT),
                        style = StenoTheme.type.bodySmall,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}
