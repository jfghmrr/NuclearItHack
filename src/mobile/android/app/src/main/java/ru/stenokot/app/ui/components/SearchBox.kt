package ru.stenokot.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import ru.stenokot.app.R
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

/**
 * Поле поиска на подложке: значок, текст и крестик очистки, когда есть что очищать.
 * Высота растёт вместе со шрифтом, поэтому текст не обрезается.
 */
@Composable
fun SearchBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
    tag: String? = null,
    clearTag: String? = null,
) {
    val colors = StenoTheme.colors
    val style = StenoTheme.type.bodySmall.copy(fontSize = StenoTheme.type.monoMedium.fontSize)
    val focus = LocalFocusManager.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.touchTarget)
            .background(colors.surfaceContainer, RoundedCornerShape(StenoRadius.field))
            .padding(start = StenoSpace.m + StenoSpace.xs / 2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s + StenoSpace.xs / 2),
    ) {
        StenoIcon(R.drawable.ic_search, colors.onSurfaceVariant, size = StenoSize.icon)
        Box(Modifier.weight(1f).padding(vertical = StenoSpace.s), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = placeholder }
                    .tagged(tag),
                textStyle = style.copy(color = colors.onSurface),
                singleLine = true,
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            )
            if (value.isEmpty()) Text(placeholder, style = style, color = colors.onSurfaceVariant)
        }
        if (value.isNotEmpty()) {
            IconButton(
                onClick = { onValueChange("") },
                modifier = Modifier.size(StenoSize.touchTarget).then(
                    if (clearTag != null) Modifier.testTag(clearTag) else Modifier,
                ),
            ) {
                StenoIcon(R.drawable.ic_close, colors.onSurfaceVariant, description = clearLabel)
            }
        }
    }
}
