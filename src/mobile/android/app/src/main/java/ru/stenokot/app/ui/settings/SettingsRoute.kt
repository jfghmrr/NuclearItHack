package ru.stenokot.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.stenokot.app.R
import ru.stenokot.app.domain.ModelReadiness
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.LinkRow
import ru.stenokot.app.ui.components.ListBlock
import ru.stenokot.app.ui.components.SectionHeader
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.StenoTopBar
import ru.stenokot.app.ui.format.modelsLabel
import ru.stenokot.app.ui.layout.CenteredContent
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.nav.Route
import ru.stenokot.app.ui.theme.LocalAppearance
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.app.ui.theme.ThemeMode

@StringRes
private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

/** Настройки: технические сведения, оформление и всё, что не нужно каждый день. */
@Composable
fun SettingsRoute(controller: StenoController, actions: AppActions) {
    val home by controller.home.collectAsStateWithLifecycle()
    val layout = LocalWindowLayout.current
    val appearance = LocalAppearance.current
    val verification = { actions.navigator.push(Route.Verification) }
    val ready = home.models.readiness == ModelReadiness.READY
    Column(Modifier.fillMaxSize()) {
        StenoTopBar(stringResource(R.string.settings_title), onBack = actions.back)
        CenteredContent(Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = layout.gutter)
                    .padding(bottom = StenoSpace.xl),
            ) {
                Section(R.string.settings_section_data) {
                    ListBlock(
                        rows = listOf(
                            {
                                LinkRow(
                                    title = stringResource(R.string.settings_models),
                                    subtitle = stringResource(
                                        if (ready) R.string.settings_models_ready else R.string.settings_models_partial,
                                        modelsLabel(home.models.items.size),
                                    ),
                                    icon = R.drawable.ic_memory,
                                    onClick = verification,
                                    tag = Tags.MODELS_ROW,
                                )
                            },
                            {
                                LinkRow(
                                    title = stringResource(R.string.settings_privacy),
                                    subtitle = stringResource(R.string.settings_privacy_hint),
                                    icon = R.drawable.ic_shield,
                                    onClick = verification,
                                )
                            },
                        ),
                    )
                }
                Section(R.string.settings_section_comfort) {
                    ListBlock(
                        rows = listOf(
                            { ThemeRow(appearance.theme, appearance::selectTheme) },
                            { MotionRow(appearance.mascotMotion, appearance::selectMotion) },
                        ),
                    )
                }
                Section(R.string.settings_section_more) {
                    ListBlock(
                        rows = listOf(
                            {
                                LinkRow(
                                    title = stringResource(R.string.settings_diagnostics),
                                    subtitle = stringResource(R.string.settings_diagnostics_hint),
                                    icon = R.drawable.ic_chart,
                                    onClick = verification,
                                    tag = Tags.DIAGNOSTICS_ROW,
                                )
                            },
                            {
                                LinkRow(
                                    title = stringResource(R.string.settings_about),
                                    subtitle = stringResource(R.string.settings_about_hint),
                                    icon = R.drawable.ic_info,
                                    onClick = { actions.navigator.push(Route.Licenses) },
                                    tag = Tags.ABOUT_ROW,
                                )
                            },
                        ),
                    )
                }
                Text(
                    text = stringResource(R.string.settings_footer),
                    modifier = Modifier.fillMaxWidth().padding(top = StenoSpace.xl),
                    style = StenoTheme.type.bodySmall,
                    color = StenoTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun Section(@StringRes title: Int, content: @Composable () -> Unit) {
    SectionHeader(stringResource(title), Modifier.padding(top = StenoSpace.m, bottom = StenoSpace.xs))
    content()
}

@Composable
private fun RowLabels(title: String, subtitle: String, modifier: Modifier = Modifier) {
    val type = StenoTheme.type
    Column(modifier) {
        Text(title, style = type.cardTitle, color = StenoTheme.colors.onSurface)
        Text(subtitle, style = type.bodySmall, color = StenoTheme.colors.onSurfaceVariant)
    }
}

/** Выбор темы. Меню открывается одной кнопкой, текущее значение видно и слышно без него. */
@Composable
private fun ThemeRow(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    var open by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.settings_theme)
    val value = stringResource(themeLabel(current))
    val shape = RoundedCornerShape(StenoRadius.m)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = StenoSize.actionHeight).padding(vertical = StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) {
        StenoIcon(if (colors.isDark) R.drawable.ic_moon else R.drawable.ic_sun, colors.primary, size = StenoSize.icon)
        RowLabels(title, stringResource(R.string.settings_theme_hint), Modifier.weight(1f))
        Box {
            Row(
                modifier = Modifier
                    .heightIn(min = StenoSize.touchTarget)
                    .clip(shape)
                    .background(colors.surfaceContainerLow, shape)
                    .border(StenoSize.hairline, colors.outlineVariant, shape)
                    .testTag(Tags.THEME_ROW)
                    .clickable(role = Role.DropdownList) { open = true }
                    .semantics {
                        contentDescription = title
                        stateDescription = value
                    }
                    .padding(horizontal = StenoSpace.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
            ) {
                Text(value, style = type.bodySmall, color = colors.onSurface)
                StenoIcon(R.drawable.ic_expand_more, colors.onSurfaceVariant, size = StenoSize.iconSmall)
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                containerColor = colors.surface,
                shape = RoundedCornerShape(StenoRadius.field),
            ) {
                ThemeMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(stringResource(themeLabel(mode)), style = type.bodySmall, color = colors.onSurface) },
                        onClick = {
                            open = false
                            onSelect(mode)
                        },
                        modifier = Modifier.testTag(Tags.themeOption(mode.name.lowercase())),
                    )
                }
            }
        }
    }
}

@Composable
private fun MotionRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val colors = StenoTheme.colors
    val title = stringResource(R.string.settings_motion)
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.actionHeight)
            .testTag(Tags.MOTION_ROW)
            .clickable(interaction, null, role = Role.Switch) { onChange(!enabled) }
            .semantics(mergeDescendants = true) { }
            .padding(vertical = StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) {
        StenoIcon(R.drawable.ic_spark, colors.primary, size = StenoSize.icon)
        RowLabels(title, stringResource(R.string.settings_motion_hint), Modifier.weight(1f))
        Switch(
            checked = enabled,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary,
                uncheckedThumbColor = colors.surface,
                uncheckedTrackColor = colors.outlineVariant,
                uncheckedBorderColor = colors.outlineVariant,
            ),
        )
    }
}
