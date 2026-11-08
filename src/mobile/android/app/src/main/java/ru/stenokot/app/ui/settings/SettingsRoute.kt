package ru.stenokot.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
import ru.stenokot.app.ui.components.resourceTags
import ru.stenokot.app.ui.format.modelsLabel
import ru.stenokot.app.ui.help.HelpTags
import ru.stenokot.app.ui.layout.CenteredContent
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.nav.Route
import ru.stenokot.app.ui.theme.LocalAppearance
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.app.ui.theme.ThemeMode
import ru.stenokot.app.ui.verification.rememberDeviceAccess

private val RowHeight = 72.dp

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
                                    modifier = Modifier.heightIn(min = RowHeight),
                                    title = stringResource(R.string.settings_models),
                                    subtitle = stringResource(
                                        if (ready) R.string.settings_models_ready else R.string.settings_models_partial,
                                        modelsLabel(home.models.items.size),
                                    ),
                                    icon = R.drawable.ic_memory,
                                    onClick = { actions.navigator.push(Route.Models) },
                                    tag = Tags.MODELS_ROW,
                                )
                            },
                            {
                                LinkRow(
                                    modifier = Modifier.heightIn(min = RowHeight),
                                    title = stringResource(R.string.settings_privacy),
                                    subtitle = stringResource(R.string.settings_privacy_hint),
                                    icon = R.drawable.ic_shield,
                                    onClick = { actions.navigator.push(Route.Privacy) },
                                    tag = Tags.PRIVACY_ROW,
                                )
                            },
                        ),
                    )
                }
                Section(R.string.settings_section_comfort) {
                    ListBlock(
                        rows = listOf(
                            { NotificationsRow() },
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
                                    modifier = Modifier.heightIn(min = RowHeight),
                                    title = stringResource(R.string.help_title),
                                    subtitle = stringResource(R.string.help_settings_hint),
                                    icon = R.drawable.ic_help,
                                    onClick = { actions.navigator.push(Route.Help) },
                                    tag = HelpTags.OPEN,
                                )
                            },
                            {
                                LinkRow(
                                    modifier = Modifier.heightIn(min = RowHeight),
                                    title = stringResource(R.string.settings_diagnostics),
                                    subtitle = stringResource(R.string.settings_diagnostics_hint),
                                    icon = R.drawable.ic_chart,
                                    onClick = { actions.navigator.push(Route.Diagnostics) },
                                    tag = Tags.DIAGNOSTICS_ROW,
                                )
                            },
                            {
                                LinkRow(
                                    modifier = Modifier.heightIn(min = RowHeight),
                                    title = stringResource(R.string.settings_about),
                                    subtitle = stringResource(R.string.settings_about_hint),
                                    icon = R.drawable.ic_info,
                                    onClick = { actions.navigator.push(Route.About) },
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

/**
 * Выбор темы. Меню открывается одной кнопкой, текущее значение видно и слышно без него. В узком окне
 * и с крупным шрифтом кнопка уходит под подпись: иначе слово «Оформление» разорвалось бы пополам.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeRow(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    var open by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.settings_theme)
    val value = stringResource(themeLabel(current))
    val shape = RoundedCornerShape(StenoRadius.m)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = RowHeight).padding(vertical = StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) {
        StenoIcon(if (colors.isDark) R.drawable.ic_moon else R.drawable.ic_sun, colors.primary, size = StenoSize.icon)
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(StenoSpace.xs),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            RowLabels(title, stringResource(R.string.settings_theme_hint))
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
                    modifier = Modifier.resourceTags(),
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
}

/** Строка с переключателем. Значение и подпись читаются вместе, состояние слышно в TalkBack. */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    @DrawableRes icon: Int,
    checked: Boolean,
    tag: String,
    onChange: (Boolean) -> Unit,
) {
    val colors = StenoTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowHeight)
            .testTag(tag)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = StenoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) {
        StenoIcon(icon, colors.primary, size = StenoSize.icon)
        RowLabels(title, subtitle, Modifier.weight(1f))
        Switch(
            checked = checked,
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

@Composable
private fun MotionRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    SwitchRow(
        title = stringResource(R.string.settings_motion),
        subtitle = stringResource(R.string.settings_motion_hint),
        icon = R.drawable.ic_spark,
        checked = enabled,
        tag = Tags.MOTION_ROW,
        onChange = onChange,
    )
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
}

/**
 * Переключатель показывает настоящее разрешение системы. Запрос звучит только по нажатию, а когда
 * система больше не покажет диалог или разрешение надо отозвать, открываются её настройки.
 */
@Composable
private fun NotificationsRow() {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val access by rememberDeviceAccess()
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val dialogBlocked = activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == false
        if (!granted && dialogBlocked) openNotificationSettings(context)
    }
    val askSystem = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    SwitchRow(
        title = stringResource(R.string.settings_notifications),
        subtitle = stringResource(if (access.notifications) R.string.settings_notifications_on else R.string.settings_notifications_off),
        icon = R.drawable.ic_notifications,
        checked = access.notifications,
        tag = Tags.NOTIFICATIONS,
        onChange = {
            if (askSystem) request.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings(context)
        },
    )
}
