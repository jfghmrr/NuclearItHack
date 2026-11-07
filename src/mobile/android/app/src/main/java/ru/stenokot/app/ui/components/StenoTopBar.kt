package ru.stenokot.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

/** Верхняя панель: кнопка назад и заголовок экрана. */
@Composable
fun StenoTopBar(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val gutter = LocalWindowLayout.current.gutter
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = if (onBack != null) StenoSpace.xs else gutter, end = gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.xs),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.testTag(Tags.BACK)) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.common_back),
                    tint = StenoTheme.colors.onSurface,
                )
            }
        }
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = StenoTheme.type.titleLarge,
            color = StenoTheme.colors.onSurface,
        )
    }
}
