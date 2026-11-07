package ru.stenokot.app.ui.verification

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import ru.stenokot.app.R
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.LinkRow
import ru.stenokot.app.ui.components.ListBlock
import ru.stenokot.app.ui.components.StatusBanner
import ru.stenokot.app.ui.nav.Route
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private const val MARK = "с."

/** О приложении: версия и пакет читаются из установленной сборки. */
@Composable
fun AboutRoute(actions: AppActions) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val facts by rememberPackageFacts()
    ServicePage(title = stringResource(R.string.settings_about), onBack = actions.back) {
        Column(verticalArrangement = Arrangement.spacedBy(StenoSpace.s)) {
            LeadTile(Modifier.clearAndSetSemantics { }) {
                Text(MARK, style = type.display, color = colors.primary)
            }
            Text(
                text = stringResource(R.string.app_name),
                modifier = Modifier.padding(top = StenoSpace.s).semantics { heading() },
                style = type.headline,
                color = colors.onSurface,
            )
            Text(stringResource(R.string.about_tagline), style = type.bodyLarge, color = colors.onSurfaceVariant)
        }
        Column {
            facts?.let {
                FactRow(stringResource(R.string.about_version), it.versionName, Tags.APP_VERSION)
                FactRow(stringResource(R.string.about_package), it.packageName, Tags.APP_PACKAGE)
            }
        }
        ListBlock(
            rows = listOf(
                {
                    LinkRow(
                        title = stringResource(R.string.about_licenses),
                        subtitle = stringResource(R.string.about_licenses_hint),
                        icon = R.drawable.ic_book,
                        onClick = { actions.navigator.push(Route.Licenses) },
                        tag = Tags.LICENSES,
                    )
                },
                {
                    LinkRow(
                        title = stringResource(R.string.privacy_title),
                        subtitle = stringResource(R.string.about_privacy_hint),
                        icon = R.drawable.ic_shield,
                        onClick = { actions.navigator.push(Route.Privacy) },
                    )
                },
            ),
        )
        StatusBanner(
            tone = BannerTone.SUCCESS,
            icon = R.drawable.ic_shield,
            body = stringResource(R.string.about_note),
        )
    }
}
