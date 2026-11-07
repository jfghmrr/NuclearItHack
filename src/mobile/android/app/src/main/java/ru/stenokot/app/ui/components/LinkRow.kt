package ru.stenokot.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import ru.stenokot.app.R
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

/**
 * Строка-переход: значок, подпись, пояснение и знак справа. Без [onClick] это просто сведения.
 * Боковых полей у строки нет: их задаёт колонка, в которой она стоит.
 */
@Composable
fun LinkRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    iconTint: Color = StenoTheme.colors.onSurfaceVariant,
    @DrawableRes trailing: Int? = R.drawable.ic_chevron_right,
    onClick: (() -> Unit)? = null,
    description: String? = null,
    tag: String? = null,
) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.actionHeight)
            .tagged(tag)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .then(
                if (description != null) {
                    Modifier.spokenAs(description, title)
                } else {
                    Modifier.semantics(mergeDescendants = true) { }
                },
            )
            .padding(vertical = StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) {
        icon?.let { StenoIcon(it, iconTint, size = StenoSize.icon) }
        Column(Modifier.weight(1f)) {
            Text(title, style = type.labelMedium, color = colors.onSurface)
            subtitle?.let { Text(it, style = type.bodySmall, color = colors.onSurfaceVariant) }
        }
        trailing?.let { StenoIcon(it, colors.onSurfaceVariant) }
    }
}
