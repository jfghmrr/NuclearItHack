package ru.stenokot.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

/** Ширина поля с таймкодом с учётом масштаба шрифта. Строка стенограммы использует то же поле. */
@Composable
fun ledgerMarginWidth(): Dp {
    val scale = LocalDensity.current.fontScale.coerceIn(1f, 1.4f)
    return StenoSize.ledgerMargin * scale
}

/** Линия поля: тонкая вертикаль, как поле в тетради. Соседние строки складывают её в одну. */
fun Modifier.marginRule(color: Color, x: Dp): Modifier = drawBehind {
    val position = x.toPx()
    drawLine(color, Offset(position, 0f), Offset(position, size.height), strokeWidth = 1f)
}

/**
 * Строка в сетке документа: слева поле с данными моноширинным шрифтом, справа текст.
 * Поэтому Главная, стенограмма и рельс этапов читаются одинаково: время на поле, смысл на странице.
 * При крупном шрифте поле исчезает, а его значение уходит в [marginFallback], заданный вызывающим.
 */
@Composable
fun LedgerRow(
    margin: String?,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleMaxLines: Int = 1,
    marginFallback: String? = subtitle,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    description: String? = null,
    tag: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val type = StenoTheme.type
    val colors = StenoTheme.colors
    val gutter = LocalWindowLayout.current.gutter
    val showMargin = !LocalWindowLayout.current.largeText
    val marginWidth = ledgerMarginWidth()
    val ruleX = gutter + marginWidth + StenoSpace.s
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (showMargin) Modifier.marginRule(colors.outlineVariant, ruleX) else Modifier),
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = StenoSize.touchTarget + StenoSpace.l)
                .tagged(tag)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (description != null) {
                        Modifier.spokenAs(description, title)
                    } else {
                        Modifier.semantics(mergeDescendants = true) { }
                    },
                )
                .padding(start = gutter, end = if (trailing != null) 0.dp else gutter),
            verticalAlignment = Alignment.Top,
        ) {
            if (showMargin) {
                Text(
                    text = margin.orEmpty(),
                    modifier = Modifier
                        .width(marginWidth)
                        .padding(top = StenoSpace.m + StenoSpace.xs / 2),
                    style = type.monoMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.End,
                )
                Spacer(Modifier.width(StenoSpace.s + StenoSpace.m))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = StenoSpace.m),
                verticalArrangement = Arrangement.spacedBy(StenoSpace.xs / 2),
            ) {
                Text(
                    text = title,
                    style = type.labelMedium,
                    color = colors.onSurface,
                    maxLines = titleMaxLines,
                    overflow = if (titleMaxLines == 1) TextOverflow.MiddleEllipsis else TextOverflow.Clip,
                )
                val secondLine = if (showMargin) subtitle else marginFallback
                secondLine?.let { Text(it, style = type.bodySmall, color = colors.onSurfaceVariant) }
                footer?.invoke(this)
            }
        }
        if (trailing != null) {
            Box(Modifier.padding(end = StenoSpace.xs)) { trailing() }
        }
    }
}
