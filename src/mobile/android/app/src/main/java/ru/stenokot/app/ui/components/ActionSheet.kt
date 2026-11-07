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
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import ru.stenokot.app.R
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private const val DISABLED_ALPHA = 0.5f

/** Заголовок нижнего листа с крестиком: закрыть можно и без жеста. */
@Composable
fun SheetHeader(title: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = StenoTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            modifier = Modifier.weight(1f).semantics { heading() },
            style = StenoTheme.type.sheetTitle,
            color = colors.onBackground,
        )
        IconButton(onClick = onClose, modifier = Modifier.testTag(Tags.SHEET_CLOSE)) {
            StenoIcon(
                icon = R.drawable.ic_close,
                tint = colors.onSurface,
                size = StenoSize.icon,
                description = stringResource(R.string.common_close),
            )
        }
    }
}

/**
 * Нижний лист без заданного содержимого: форма и цвета макета. Тянуть его можно только вниз, поэтому
 * лист высотой с экран всё равно закрывается жестом.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StenoSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
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
        content = content,
    )
}

/** Нижний лист с заголовком и строками действий, как на макете. */
@Composable
fun ActionSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = StenoTheme.colors
    StenoSheet(onDismiss) {
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
                style = StenoTheme.type.sheetTitle,
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
