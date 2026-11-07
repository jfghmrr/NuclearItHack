package ru.stenokot.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import ru.stenokot.app.R
import ru.stenokot.app.ui.theme.StenoRadius
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
    iconTint: Color = StenoTheme.colors.primary,
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
            Text(title, style = type.cardTitle, color = colors.onSurface)
            subtitle?.let { Text(it, style = type.bodySmall, color = colors.onSurfaceVariant) }
        }
        trailing?.let { StenoIcon(it, colors.onSurfaceVariant) }
    }
}

/**
 * Блок строк на поверхности карточки. [rows] выводятся по порядку, между ними тонкая линия,
 * которая начинается от края текста и не доходит до рамки.
 */
@Composable
fun ListBlock(modifier: Modifier = Modifier, rows: List<@Composable () -> Unit>) {
    val colors = StenoTheme.colors
    val shape = RoundedCornerShape(StenoRadius.card)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface, shape)
            .border(StenoSize.hairline, colors.outlineVariant, shape)
            .padding(horizontal = StenoSpace.l),
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) Spacer(Modifier.fillMaxWidth().height(StenoSize.hairline).background(colors.outlineVariant))
            row()
        }
    }
}
