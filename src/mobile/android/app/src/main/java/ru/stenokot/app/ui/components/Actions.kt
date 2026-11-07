package ru.stenokot.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private val ActionShape = RoundedCornerShape(StenoRadius.xl)
private val ActionPadding = PaddingValues(horizontal = 20.dp, vertical = StenoSpace.m)

/** Подпись прижата влево, значок вправо: кнопка читается как строка документа, а не как плашка. */
@Composable
private fun RowScope.ActionContent(text: String, @DrawableRes icon: Int?) {
    Text(
        text = text,
        modifier = Modifier.weight(1f),
        style = StenoTheme.type.labelLarge,
        textAlign = TextAlign.Start,
    )
    if (icon != null) {
        Spacer(Modifier.width(StenoSpace.m))
        Icon(painterResource(icon), contentDescription = null, Modifier.size(StenoSize.icon))
    }
}

/** Главное действие экрана. На экране такое одно. */
@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    tag: String? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = StenoSize.actionHeight).tagged(tag),
        enabled = enabled,
        shape = ActionShape,
        contentPadding = ActionPadding,
        elevation = null,
    ) { ActionContent(text, icon) }
}

/** Тональное действие: важное, но не главное. */
@Composable
fun TonalAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    tag: String? = null,
) {
    val colors = StenoTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = StenoSize.actionHeight).tagged(tag),
        enabled = enabled,
        shape = ActionShape,
        contentPadding = ActionPadding,
        elevation = null,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer,
        ),
    ) { ActionContent(text, icon) }
}

/** Контурное действие: второстепенное рядом с главным. */
@Composable
fun OutlinedAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    tag: String? = null,
) {
    val colors = StenoTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = StenoSize.actionHeight).tagged(tag),
        enabled = enabled,
        shape = ActionShape,
        contentPadding = ActionPadding,
        border = BorderStroke(StenoSize.hairline, if (enabled) colors.outline else colors.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
    ) { ActionContent(text, icon) }
}

/** Тихое текстовое действие без контура. */
@Composable
fun QuietAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = StenoSize.touchTarget).tagged(tag),
        enabled = enabled,
        shape = RoundedCornerShape(StenoRadius.m),
        contentPadding = PaddingValues(horizontal = StenoSpace.s, vertical = StenoSpace.s),
    ) {
        Text(text, style = StenoTheme.type.labelMedium, textAlign = TextAlign.End)
    }
}

/** Добавляет testTag, если он задан. */
internal fun Modifier.tagged(tag: String?): Modifier = if (tag != null) testTag(tag) else this
