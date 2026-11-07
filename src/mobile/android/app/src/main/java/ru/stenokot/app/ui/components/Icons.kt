package ru.stenokot.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import ru.stenokot.app.ui.theme.StenoSize

/** Контурный значок из единого набора приложения. По умолчанию декоративный. */
@Composable
fun StenoIcon(
    @DrawableRes icon: Int,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = StenoSize.iconMedium,
    description: String? = null,
) {
    Icon(painterResource(icon), description, modifier.size(size), tint = tint)
}
