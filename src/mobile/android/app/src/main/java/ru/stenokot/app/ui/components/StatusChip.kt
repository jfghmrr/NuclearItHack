package ru.stenokot.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

/** Метка состояния: значок и слова на тоновой подложке. Высота растёт вместе со шрифтом. */
@Composable
fun StatusChip(
    text: String,
    @DrawableRes icon: Int,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    val palette = toneColors(tone)
    Row(
        modifier = modifier
            .heightIn(min = 28.dp)
            .background(palette.container, RoundedCornerShape(StenoRadius.m))
            .padding(horizontal = StenoSpace.m, vertical = StenoSpace.xs)
            .tagged(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        StenoIcon(icon, palette.icon, size = StenoSize.iconSmall)
        Text(text, style = StenoTheme.type.bodySmall, color = palette.content)
    }
}
