package ru.stenokot.app.ui.verification

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.stenokot.app.R
import ru.stenokot.app.domain.StenoController
import ru.stenokot.app.domain.VerificationUi
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.LinkRow
import ru.stenokot.app.ui.components.ListBlock
import ru.stenokot.app.ui.components.SectionHeader
import ru.stenokot.app.ui.components.StatusBanner
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.toneColors
import ru.stenokot.app.ui.nav.Route
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

/** Обе проверки должны согласиться: конвейер читает пакет при старте, экран читает его сам. */
private fun internetAbsent(verification: VerificationUi, facts: PackageFacts?): Boolean =
    verification.internetPermissionAbsent && facts?.internetAbsent != false

private fun networkStateAbsent(verification: VerificationUi, facts: PackageFacts?): Boolean =
    verification.networkStatePermissionAbsent && facts?.networkStateAbsent != false

/** Приватность: сначала понятным языком, сразу за этим проверка по установленному пакету. */
@Composable
fun PrivacyRoute(controller: StenoController, actions: AppActions) {
    val verification by controller.verification.collectAsStateWithLifecycle()
    val facts by rememberPackageFacts()
    ServicePage(title = stringResource(R.string.privacy_title), onBack = actions.back) {
        PrivacyHero()
        NetworkProof(verification, facts)
        Column {
            PrivacyPoint(R.drawable.ic_lock, R.string.privacy_audio_title, R.string.privacy_audio_body)
            Hairline()
            PrivacyPoint(R.drawable.ic_share, R.string.privacy_share_title, R.string.privacy_share_body)
            Hairline()
            PrivacyPoint(R.drawable.ic_mic, R.string.privacy_mic_title, R.string.privacy_mic_body)
        }
        ListBlock(
            rows = listOf(
                {
                    LinkRow(
                        title = stringResource(R.string.privacy_permissions),
                        subtitle = stringResource(R.string.privacy_permissions_hint),
                        icon = R.drawable.ic_shield,
                        onClick = { actions.navigator.push(Route.Permissions) },
                        tag = Tags.PERMISSIONS,
                    )
                },
            ),
        )
    }
}

@Composable
private fun PrivacyHero() {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.primaryContainer, RoundedCornerShape(StenoRadius.xxl))
            .padding(StenoSpace.xl),
        verticalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) {
        StenoIcon(R.drawable.ic_shield, colors.primary, size = StenoSize.iconLarge)
        Text(
            text = stringResource(R.string.privacy_hero_title),
            modifier = Modifier.semantics { heading() },
            style = type.headline,
            color = colors.primary,
        )
        Text(stringResource(R.string.privacy_hero_body), style = type.bodyLarge, color = colors.onPrimaryContainer)
    }
}

/** Проверка пакета на этом устройстве. Если разрешение нашлось, блок сразу становится красным. */
@Composable
private fun NetworkProof(verification: VerificationUi, facts: PackageFacts?) {
    val type = StenoTheme.type
    val internet = internetAbsent(verification, facts)
    val network = networkStateAbsent(verification, facts)
    val clean = internet && network
    val palette = toneColors(if (clean) BannerTone.SUCCESS else BannerTone.ERROR)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.container, RoundedCornerShape(StenoRadius.card))
            .padding(StenoSpace.l),
        verticalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(StenoSpace.m)) {
            StenoIcon(if (clean) R.drawable.ic_wifi_off else R.drawable.ic_error, palette.icon, size = StenoSize.icon)
            Text(
                text = stringResource(if (clean) R.string.scr08_offline else R.string.scr08_online_found),
                modifier = Modifier.weight(1f).testTag(Tags.OFFLINE),
                style = type.labelMedium,
                color = palette.content,
            )
        }
        PermissionLine("INTERNET", internet, palette.content)
        PermissionLine("ACCESS_NETWORK_STATE", network, palette.content)
        facts?.let {
            Text(
                text = stringResource(
                    R.string.scr08_package_checked,
                    it.packageName,
                    verification.appVersion.ifEmpty { it.versionName },
                ),
                style = type.bodySmall,
                color = palette.content,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionLine(name: String, absent: Boolean, tint: Color) {
    val type = StenoTheme.type
    FlowRow(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name, style = type.monoSmall, color = tint)
        Text(
            text = stringResource(if (absent) R.string.scr08_permission_absent else R.string.scr08_permission_present),
            style = type.bodySmall,
            color = tint,
        )
    }
}

@Composable
private fun PrivacyPoint(@DrawableRes icon: Int, @StringRes title: Int, @StringRes body: Int) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { }
            .padding(vertical = StenoSpace.m),
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) {
        StenoIcon(icon, colors.primary, size = StenoSize.icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(StenoSpace.xs / 2)) {
            Text(stringResource(title), style = type.cardTitle, color = colors.onSurface)
            Text(stringResource(body), style = type.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun Hairline() {
    Spacer(Modifier.fillMaxWidth().height(StenoSize.hairline).background(StenoTheme.colors.outlineVariant))
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    runCatching { context.startActivity(intent) }
}

/** Разрешения: что приложение может и зачем, ниже настоящий список из пакета. */
@Composable
fun PermissionsRoute(controller: StenoController, actions: AppActions) {
    val verification by controller.verification.collectAsStateWithLifecycle()
    val facts by rememberPackageFacts()
    val access by rememberDeviceAccess()
    val context = LocalContext.current
    val type = StenoTheme.type
    val colors = StenoTheme.colors
    val clean = internetAbsent(verification, facts) && networkStateAbsent(verification, facts)
    val settings = { openAppSettings(context) }
    ServicePage(title = stringResource(R.string.permissions_title), onBack = actions.back) {
        Column {
            SectionHeader(stringResource(R.string.permissions_section))
            ListBlock(
                rows = listOf(
                    {
                        AccessRow(
                            icon = R.drawable.ic_mic,
                            title = R.string.permissions_mic,
                            hint = R.string.permissions_mic_hint,
                            state = if (access.microphone) R.string.permissions_mic_on else R.string.permissions_mic_off,
                            onClick = settings,
                        )
                    },
                    {
                        AccessRow(
                            icon = R.drawable.ic_notifications,
                            title = R.string.permissions_notifications,
                            hint = R.string.permissions_notifications_hint,
                            state = if (access.notifications) {
                                R.string.permissions_notifications_on
                            } else {
                                R.string.permissions_notifications_off
                            },
                            onClick = settings,
                        )
                    },
                    {
                        AccessRow(
                            icon = R.drawable.ic_audio_file,
                            title = R.string.permissions_audio,
                            hint = R.string.permissions_audio_hint,
                            state = if (access.audioFiles) R.string.permissions_audio_on else R.string.permissions_audio_off,
                            onClick = settings,
                        )
                    },
                ),
            )
        }
        StatusBanner(
            tone = if (clean) BannerTone.SUCCESS else BannerTone.ERROR,
            icon = if (clean) R.drawable.ic_shield else R.drawable.ic_error,
            body = stringResource(if (clean) R.string.permissions_network_clean else R.string.scr08_online_found),
        )
        Column(verticalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
            SectionHeader(stringResource(R.string.permissions_technical), count = facts?.permissions?.size)
            Text(stringResource(R.string.permissions_technical_hint), style = type.bodySmall, color = colors.onSurfaceVariant)
            facts?.let { PackagePermissions(it.permissions) }
        }
        Text(stringResource(R.string.permissions_note), style = type.bodySmall, color = colors.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccessRow(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    @StringRes hint: Int,
    @StringRes state: Int,
    onClick: () -> Unit,
) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.actionHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { }
            .padding(vertical = StenoSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) {
        StenoIcon(icon, colors.primary, size = StenoSize.icon)
        Column(Modifier.weight(1f)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(title), style = type.cardTitle, color = colors.onSurface)
                Text(stringResource(state), style = type.bodySmall, color = colors.primary)
            }
            Text(stringResource(hint), style = type.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun PackagePermissions(permissions: List<String>) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer, RoundedCornerShape(StenoRadius.field))
            .testTag(Tags.LIST_PERMISSIONS)
            .padding(horizontal = StenoSpace.l, vertical = StenoSpace.m),
        verticalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        permissions.forEach { name ->
            Text(shortPermission(name), style = type.monoSmall, color = colors.onSurfaceVariant)
        }
    }
}
