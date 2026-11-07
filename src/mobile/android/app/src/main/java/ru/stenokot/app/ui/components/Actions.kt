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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private val ActionShape = RoundedCornerShape(StenoRadius.button)
private val ActionPadding = PaddingValues(horizontal = StenoSpace.l, vertical = StenoSpace.m)
private val DensePadding = PaddingValues(horizontal = StenoSpace.s, vertical = StenoSpace.s)
private val IconGap = 10.dp
private val DenseIconGap = 6.dp

/** Значок слева от подписи, всё по центру. При крупном шрифте значок уступает место словам. */
@Composable
private fun RowScope.ActionContent(text: String, @DrawableRes icon: Int?, dense: Boolean) {
    if (icon != null && !LocalWindowLayout.current.largeText) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(StenoSize.icon))
        Spacer(Modifier.width(if (dense) DenseIconGap else IconGap))
    }
    Text(
        text = text,
        modifier = Modifier.weight(1f, fill = false),
        style = StenoTheme.type.labelLarge,
        textAlign = TextAlign.Center,
    )
}

/** Главное действие экрана. На экране такое одно. [dense] для пары кнопок в одну строку. */
@Composable
fun PrimaryAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    tag: String? = null,
    dense: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = StenoSize.actionHeight).tagged(tag),
        enabled = enabled,
        shape = ActionShape,
        contentPadding = if (dense) DensePadding else ActionPadding,
        elevation = null,
    ) { ActionContent(text, icon, dense) }
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
            containerColor = colors.primaryContainer,
            contentColor = colors.primary,
        ),
    ) { ActionContent(text, icon, false) }
}

/** Второстепенное действие рядом с главным: поверхность и тонкий контур. */
@Composable
fun OutlinedAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    tag: String? = null,
    dense: Boolean = false,
) {
    val colors = StenoTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = StenoSize.actionHeight).tagged(tag),
        enabled = enabled,
        shape = ActionShape,
        contentPadding = if (dense) DensePadding else ActionPadding,
        border = BorderStroke(StenoSize.hairline, colors.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = colors.surface,
            contentColor = colors.onSurface,
        ),
    ) { ActionContent(text, icon, dense) }
}

/** Действие без подложки на всю ширину: второй выход с экрана. */
@Composable
fun GhostAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    tag: String? = null,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = StenoSize.touchTarget).tagged(tag),
        enabled = enabled,
        shape = ActionShape,
        contentPadding = DensePadding,
        colors = ButtonDefaults.textButtonColors(contentColor = StenoTheme.colors.primary),
    ) { ActionContent(text, icon, false) }
}

/** Опасное действие: удаление, которое нельзя отменить. */
@Composable
fun DangerAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tag: String? = null,
) {
    val colors = StenoTheme.colors
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = StenoSize.touchTarget).tagged(tag),
        enabled = enabled,
        shape = ActionShape,
        contentPadding = DensePadding,
        elevation = null,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.errorContainer,
            contentColor = colors.error,
        ),
    ) { ActionContent(text, null, false) }
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
        Text(text, style = StenoTheme.type.labelLarge, textAlign = TextAlign.Center)
    }
}

/**
 * Делает testTag видимыми как resource-id. В корне приложения это уже включено, но диалог
 * живёт в своём окне, поэтому его содержимому нужен свой вызов.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.resourceTags(): Modifier = semantics { testTagsAsResourceId = true }

/** Добавляет testTag, если он задан. */
internal fun Modifier.tagged(tag: String?): Modifier = if (tag != null) testTag(tag) else this

/**
 * Заменяет содержимое узла одной фразой для TalkBack, а видимый текст оставляет для автоматики.
 * Ставится после `clickable` и `testTag`: всё, что стоит правее, сбрасывается.
 */
internal fun Modifier.spokenAs(description: String, visibleText: String): Modifier = clearAndSetSemantics {
    contentDescription = description
    text = AnnotatedString(visibleText)
}
