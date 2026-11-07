package ru.stenokot.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private val DialogMaxWidth = 356.dp
private val DialogPadding = 24.dp
private val DialogTitleSize = 23.sp
private val DialogTitleLine = 30.sp
private const val SCRIM_LIGHT = 0.33f
private const val SCRIM_DARK = 0.55f

/**
 * Диалог подтверждения: значок, вопрос, пояснение и действия столбиком. Содержимое прокручивается,
 * поэтому при крупном шрифте на маленьком экране ни одна кнопка не теряется.
 */
@Composable
fun StenoDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    iconTint: Color = StenoTheme.colors.error,
    body: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    actions: @Composable ColumnScope.() -> Unit,
) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val shape = RoundedCornerShape(StenoRadius.dialog)
    Dialog(onDismissRequest = onDismiss) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.setDimAmount(if (colors.isDark) SCRIM_DARK else SCRIM_LIGHT)
        }
        Column(
            modifier = modifier
                .resourceTags()
                .widthIn(max = DialogMaxWidth)
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surface, shape)
                .verticalScroll(rememberScrollState())
                .padding(DialogPadding),
        ) {
            icon?.let {
                StenoIcon(it, iconTint, Modifier.padding(bottom = StenoSpace.l), size = StenoSize.iconLarge)
            }
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = type.headline.copy(fontSize = DialogTitleSize, lineHeight = DialogTitleLine),
                color = colors.onSurface,
            )
            body?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(top = StenoSpace.m),
                    style = type.bodyMedium.copy(fontSize = type.monoMedium.fontSize),
                    color = colors.onSurfaceVariant,
                )
            }
            content?.invoke(this)
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = StenoSpace.xl),
                verticalArrangement = Arrangement.spacedBy(StenoSpace.s + StenoSpace.xs / 2),
                content = actions,
            )
        }
    }
}
