package ru.stenokot.app.ui.licenses

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.stenokot.app.R
import ru.stenokot.app.ui.AppActions
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.LinkRow
import ru.stenokot.app.ui.components.SectionHeader
import ru.stenokot.app.ui.components.StenoTopBar
import ru.stenokot.app.ui.layout.CenteredContent
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.nav.Route
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private const val LICENSE_DIR = "licenses/"

/** Строка перечня с её порядковым номером во всём списке. */
private data class NumberedEntry(val index: Int, val entry: LicenseEntry)

/** Перечень шрифтов, моделей и библиотек. Открывается с экрана проверки. */
@Composable
fun LicensesRoute(actions: AppActions) {
    val layout = LocalWindowLayout.current
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    Column(Modifier.fillMaxSize()) {
        StenoTopBar(stringResource(R.string.licenses_title), onBack = actions.back)
        CenteredContent(Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(Tags.LIST_LICENSES),
                contentPadding = PaddingValues(start = layout.gutter, end = layout.gutter, bottom = StenoSpace.l),
            ) {
                item(key = "intro", contentType = "intro") {
                    Text(
                        text = stringResource(R.string.licenses_intro),
                        modifier = Modifier.padding(bottom = StenoSpace.s),
                        style = type.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                var offset = 0
                LicenseCatalog.groups.forEach { group ->
                    item(key = group.title, contentType = "header") { SectionHeader(stringResource(group.title)) }
                    val rows = group.entries.mapIndexed { index, entry -> NumberedEntry(offset + index, entry) }
                    items(rows, key = { it.entry.id }, contentType = { "row" }) { (index, entry) ->
                        LicenseRow(index, entry) { actions.navigator.push(Route.LicenseText(entry.id)) }
                    }
                    offset += group.entries.size
                }
            }
        }
    }
}

@Composable
private fun LicenseRow(index: Int, entry: LicenseEntry, onClick: () -> Unit) {
    LinkRow(
        title = entry.name,
        subtitle = stringResource(R.string.licenses_row, entry.role, entry.license),
        onClick = onClick,
        description = stringResource(R.string.licenses_row_spoken, entry.name, entry.role, entry.license),
        tag = Tags.rowLicense(index),
    )
}

private fun readLicense(context: Context, file: String): List<String> = runCatching {
    context.assets.open(LICENSE_DIR + file).bufferedReader().use { licenseParagraphs(it.readText()) }
}.getOrDefault(emptyList())

/** Текст лицензии. Абзацы идут списком, поэтому длинный текст не мешает прокрутке. */
@Composable
fun LicenseTextRoute(entryId: String, actions: AppActions) {
    val layout = LocalWindowLayout.current
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val context = LocalContext.current
    val entry = LicenseCatalog.entry(entryId)
    val paragraphs by produceState<List<String>?>(null, entryId) {
        value = entry?.let { found -> withContext(Dispatchers.IO) { readLicense(context, found.file) } }.orEmpty()
    }
    Column(Modifier.fillMaxSize()) {
        StenoTopBar(entry?.name ?: stringResource(R.string.licenses_title), onBack = actions.back)
        CenteredContent(Modifier.weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(Tags.LICENSE_TEXT),
                contentPadding = PaddingValues(start = layout.gutter, end = layout.gutter, bottom = StenoSpace.l),
                verticalArrangement = Arrangement.spacedBy(StenoSpace.m),
            ) {
                if (entry != null) {
                    item(key = "head", contentType = "head") {
                        Column(verticalArrangement = Arrangement.spacedBy(StenoSpace.xs)) {
                            Text("${entry.license}, ${entry.holder}", style = type.labelMedium, color = colors.onSurface)
                            Text(stringResource(R.string.licenses_text_note), style = type.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
                val text = paragraphs
                if (text != null && text.isEmpty()) {
                    item(key = "missing", contentType = "missing") {
                        Text(stringResource(R.string.licenses_text_missing), style = type.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
                itemsIndexed(text.orEmpty(), key = { index, _ -> index }, contentType = { _, _ -> "paragraph" }) { _, paragraph ->
                    Text(
                        text = paragraph,
                        style = if (isLicenseHeading(paragraph)) type.labelMedium else type.bodyMedium,
                        color = colors.onSurface,
                    )
                }
            }
        }
    }
}
