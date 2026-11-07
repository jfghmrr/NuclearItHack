package ru.stenokot.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import ru.stenokot.app.R
import ru.stenokot.app.ui.format.JobBadge
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

@DrawableRes
private fun badgeIcon(badge: JobBadge): Int = when (badge) {
    JobBadge.READY, JobBadge.SAVED -> R.drawable.ic_check_circle_fill
    JobBadge.PROCESSING -> R.drawable.ic_hourglass_top
    JobBadge.WAITING -> R.drawable.ic_schedule
    JobBadge.PAUSED -> R.drawable.ic_pause_circle
    JobBadge.ERROR -> R.drawable.ic_error
    JobBadge.CANCELLED -> R.drawable.ic_cancel
}

@Composable
private fun badgeColor(badge: JobBadge): Color {
    val colors = StenoTheme.colors
    return when (badge) {
        JobBadge.READY, JobBadge.SAVED -> colors.success
        JobBadge.PROCESSING -> colors.primary
        JobBadge.WAITING, JobBadge.CANCELLED -> colors.onSurfaceVariant
        JobBadge.PAUSED -> colors.warning
        JobBadge.ERROR -> colors.error
    }
}

/** Статус задачи: значок и слово. Цвет только дополняет их. */
@Composable
fun JobStatusLabel(badge: JobBadge, modifier: Modifier = Modifier, extra: String? = null) {
    val color = badgeColor(badge)
    val type = StenoTheme.type
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.xs),
    ) {
        StenoIcon(badgeIcon(badge), color, size = StenoSize.iconSmall)
        Text(badge.label, style = type.labelSmall, color = color)
        extra?.let {
            Text(it, style = type.bodySmall, color = StenoTheme.colors.onSurfaceVariant)
        }
    }
}
