package ru.stenokot.app.ui.verification

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.BottomActions
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.StenoTopBar
import ru.stenokot.app.ui.components.spokenAs
import ru.stenokot.app.ui.components.tagged
import ru.stenokot.app.ui.components.toneColors
import ru.stenokot.app.ui.layout.CenteredContent
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private val TileSize = 72.dp

/**
 * Каркас служебного экрана: панель, прокручиваемая колонка и необязательная панель действий [dock].
 * На коротком экране и с крупным шрифтом действия уходят в конец колонки, иначе текста не будет видно.
 */
@Composable
internal fun ServicePage(
    title: String,
    onBack: () -> Unit,
    dock: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val layout = LocalWindowLayout.current
    val pinned = dock != null && !layout.hintsInContent
    Column(Modifier.fillMaxSize()) {
        StenoTopBar(title, onBack = onBack)
        CenteredContent(Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = layout.gutter)
                    .padding(top = StenoSpace.s, bottom = StenoSpace.xl),
                verticalArrangement = Arrangement.spacedBy(StenoSpace.m),
            ) {
                content()
                if (!pinned) dock?.invoke(this)
            }
        }
        if (pinned) BottomActions(content = dock)
    }
}

/**
 * Строка фактов: подпись слева, значение справа, длинное значение уходит на новую строку.
 * Внутри блока с собственными линиями [divider] отключается. Строка с [tag] отдаёт автоматике само
 * значение, а TalkBack слышит его вместе с подписью.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FactRow(
    label: String,
    value: String,
    tag: String? = null,
    valueStyle: TextStyle = StenoTheme.type.labelMedium,
    divider: Boolean = true,
) {
    val colors = StenoTheme.colors
    val line = colors.outlineVariant
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(
                if (divider) {
                    Modifier.drawBehind {
                        drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), StenoSize.hairline.toPx())
                    }
                } else {
                    Modifier
                },
            )
            .tagged(tag)
            .then(
                if (tag != null) {
                    Modifier.spokenAs("$label: $value", value)
                } else {
                    Modifier.semantics(mergeDescendants = true) { }
                },
            )
            .padding(vertical = StenoSpace.s),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(StenoSpace.xs / 2),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = StenoTheme.type.bodyMedium, color = colors.onSurfaceVariant)
        Text(value, style = valueStyle, color = colors.onSurface)
    }
}

/** Плашка состояния: значок и короткая подпись. Цвет всегда дублируется словами. */
@Composable
internal fun StateChip(
    text: String,
    @DrawableRes icon: Int,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    tag: String? = null,
) {
    val palette = toneColors(tone)
    Row(
        modifier = modifier
            .background(palette.container, RoundedCornerShape(StenoRadius.m))
            .padding(horizontal = StenoSpace.m, vertical = StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        StenoIcon(icon, palette.icon, size = StenoSize.iconMedium)
        Text(text, Modifier.tagged(tag), style = StenoTheme.type.bodySmall, color = palette.content)
    }
}

/** Плитка-знак в начале карточки. */
@Composable
internal fun LeadTile(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .padding(vertical = StenoSpace.xs)
            .size(TileSize)
            .background(StenoTheme.colors.primaryContainer, RoundedCornerShape(StenoRadius.card)),
        contentAlignment = Alignment.Center,
    ) { content() }
}
