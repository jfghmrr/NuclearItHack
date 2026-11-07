package ru.stenokot.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.stenokot.app.R
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private val BarEdge = 10.dp

/** Верхняя панель: кнопка назад, заголовок с необязательной подписью и действия справа. */
@Composable
fun StenoTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.topBarHeight)
            .padding(
                start = if (onBack != null) BarEdge else LocalWindowLayout.current.gutter,
                end = if (actions != null) BarEdge else LocalWindowLayout.current.gutter,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.testTag(Tags.BACK)) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.common_back),
                    tint = colors.onSurface,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = type.titleLarge,
                color = colors.onSurface,
            )
            subtitle?.let { Text(it, style = type.meta, color = colors.onSurfaceVariant) }
        }
        actions?.invoke(this)
    }
}
