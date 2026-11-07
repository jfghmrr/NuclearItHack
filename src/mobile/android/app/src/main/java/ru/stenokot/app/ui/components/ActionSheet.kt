package ru.stenokot.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private const val DISABLED_ALPHA = 0.5f

/** Нижний лист с заголовком и строками действий, как на макете. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = StenoTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.resourceTags(),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.background,
        contentColor = colors.onSurface,
        scrimColor = colors.scrim,
        shape = RoundedCornerShape(topStart = StenoRadius.sheet, topEnd = StenoRadius.sheet),
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.outlineVariant) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = LocalWindowLayout.current.gutter)
                .padding(bottom = StenoSpace.l),
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(bottom = StenoSpace.s).semantics { heading() },
                style = StenoTheme.type.headline,
                color = colors.onBackground,
            )
            content()
        }
    }
}

/** Строка действия в нижнем листе: значок, подпись и пояснение. */
@Composable
fun ActionSheetRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    tag: String? = null,
) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val titleColor = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = DISABLED_ALPHA)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.actionHeight)
            .tagged(tag)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { }
            .padding(vertical = StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) {
        icon?.let { StenoIcon(it, colors.primary, size = StenoSize.icon) }
        Column(Modifier.weight(1f)) {
            Text(title, style = type.labelMedium, color = titleColor)
            subtitle?.let { Text(it, style = type.meta, color = colors.onSurfaceVariant) }
        }
    }
}
