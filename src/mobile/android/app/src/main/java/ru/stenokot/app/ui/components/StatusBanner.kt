package ru.stenokot.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

/** Смысл баннера. Красный только там, где нет файла: сбой протокола это предупреждение. */
enum class BannerTone { NEUTRAL, WORKING, SUCCESS, WARNING, ERROR }

/** Цвета баннера одного тона. */
@Immutable
data class ToneColors(val container: Color, val content: Color, val icon: Color)

/** Цвета для тона [tone]. */
@Composable
@ReadOnlyComposable
fun toneColors(tone: BannerTone): ToneColors {
    val colors = StenoTheme.colors
    return when (tone) {
        BannerTone.NEUTRAL -> ToneColors(colors.surfaceContainer, colors.onSurfaceVariant, colors.onSurfaceVariant)
        BannerTone.WORKING -> ToneColors(colors.primaryContainer, colors.onPrimaryContainer, colors.primary)
        BannerTone.SUCCESS -> ToneColors(colors.successContainer, colors.onSuccessContainer, colors.success)
        BannerTone.WARNING -> ToneColors(colors.warningContainer, colors.onWarningContainer, colors.warning)
        BannerTone.ERROR -> ToneColors(colors.errorContainer, colors.onErrorContainer, colors.error)
    }
}

/**
 * Статусная плашка: значок, заголовок, пояснение и необязательное действие в слоте [action].
 * При [polite] изменения озвучиваются TalkBack, не перебивая речь.
 */
@Composable
fun StatusBanner(
    tone: BannerTone,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    title: String? = null,
    body: String? = null,
    tag: String? = null,
    polite: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val palette = toneColors(tone)
    val type = StenoTheme.type
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.touchTarget)
            .background(palette.container, RoundedCornerShape(StenoRadius.field))
            .padding(horizontal = StenoSpace.l, vertical = StenoSpace.m)
            .then(if (polite) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
            .tagged(tag),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) {
        StenoIcon(icon, palette.icon, size = StenoSize.iconMedium)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StenoSpace.xs / 2)) {
            title?.let {
                Text(it, style = type.bodySmall.copy(fontWeight = FontWeight.W700), color = palette.content)
            }
            body?.let { Text(it, style = type.bodySmall, color = palette.content) }
            action?.invoke()
        }
    }
}
