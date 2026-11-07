package ru.stenokot.app.ui.result

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.stenokot.app.R
import ru.stenokot.app.domain.ExportKind
import ru.stenokot.app.domain.OutputFileUi
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.OutlinedAction
import ru.stenokot.app.ui.components.PrimaryAction
import ru.stenokot.app.ui.components.SheetHeader
import ru.stenokot.app.ui.components.StatusBanner
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.StenoSheet
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoRadius
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private const val DOCS_AUTHORITY = "com.android.externalstorage.documents"
private const val PRIMARY_VOLUME = "primary:"
private const val APP_FOLDER_MARK = "Android/data/"
private const val MIME_JSON = "application/json"
private const val WRITE_POLLS = 30
private const val WRITE_POLL_MS = 100L
private const val DISABLED_ALPHA = 0.5f
private val SelectedBorder = 1.5.dp

/** Файл лежит в папке приложения: запись в Download не удалась. */
internal val OutputFileUi.inAppFolder: Boolean get() = APP_FOLDER_MARK in path

/** Папка файла относительно общего хранилища. Конвейер отдаёт полный путь, система ждёт относительный. */
internal fun outputFolder(path: String, storageRoot: String = Environment.getExternalStorageDirectory().path): String =
    path.removePrefix(storageRoot).trimStart('/').substringBeforeLast('/', "")

/**
 * Находит запись файла в Download. Нужна, если конвейер не передал адрес сам: файл принадлежит
 * приложению, поэтому разрешения не требуются.
 */
private fun findInDownloads(context: Context, path: String): Uri? {
    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
    val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
    val arguments = arrayOf(outputFolder(path) + "/", path.substringAfterLast('/'))
    val columns = arrayOf(MediaStore.MediaColumns._ID)
    return runCatching {
        context.contentResolver.query(collection, columns, selection, arguments, null)?.use { row ->
            if (row.moveToFirst()) ContentUris.withAppendedId(collection, row.getLong(0)) else null
        }
    }.getOrNull()
}

/**
 * Открывает системное окно «Поделиться». Адрес дублируется в ClipData: без него окно не получает
 * права на чтение и вместо имени файла показывает номер записи. Значение false означает, что
 * отдавать нечего.
 */
internal suspend fun shareOutput(context: Context, output: OutputFileUi, title: String): Boolean {
    val uri = withContext(Dispatchers.IO) {
        output.contentUri?.let(Uri::parse) ?: findInDownloads(context, output.path)
    } ?: return false
    val name = output.path.substringAfterLast('/')
    val send = Intent(Intent.ACTION_SEND)
        .setType(MIME_JSON)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TITLE, name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(name, uri)
    return launch(context, Intent.createChooser(send, title))
}

/** Копирует текст в буфер обмена. */
internal fun copyText(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun launch(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

/**
 * Открывает папку с файлом в системном файловом менеджере. На образе без него остаётся список
 * загрузок, а папка приложения снаружи недоступна, и тогда возвращается false.
 */
internal fun openFolder(context: Context, output: OutputFileUi): Boolean {
    if (output.inAppFolder) return false
    val document = DocumentsContract.buildDocumentUri(DOCS_AUTHORITY, PRIMARY_VOLUME + outputFolder(output.path))
    val folder = Intent(Intent.ACTION_VIEW).setDataAndType(document, DocumentsContract.Document.MIME_TYPE_DIR)
    return launch(context, folder) || launch(context, Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
}

/**
 * Ждёт, пока в выбранном файле появятся байты. Контроллер пишет в фоне и об успехе не сообщает,
 * а обещать «Файл сохранён» без самого файла нельзя.
 */
internal suspend fun awaitWritten(context: Context, target: Uri): Boolean = withContext(Dispatchers.IO) {
    repeat(WRITE_POLLS) {
        delay(WRITE_POLL_MS)
        val length = runCatching {
            context.contentResolver.openAssetFileDescriptor(target, "r")?.use { it.length }
        }.getOrNull()
        if (length != null && length > 0) return@withContext true
    }
    false
}

/**
 * Строка в начале списка: куда автоматически сохранён файл. Одна строка и только до первой прокрутки,
 * сведения о нём открываются нажатием.
 */
@Composable
internal fun OutputLine(output: OutputFileUi, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = StenoTheme.colors
    val path = stringResource(
        if (output.inAppFolder) R.string.scr04_saved_fallback else R.string.common_saved,
        output.path,
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.touchTarget)
            .clickable(
                onClickLabel = stringResource(R.string.scr04_panel_open),
                role = Role.Button,
                onClick = onOpen,
            )
            .padding(horizontal = LocalWindowLayout.current.gutter + StenoSpace.s, vertical = StenoSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        StenoIcon(
            icon = if (output.validated) R.drawable.ic_check_circle else R.drawable.ic_warning,
            tint = if (output.validated) colors.success else colors.warning,
            size = StenoSize.iconSmall,
        )
        Text(
            text = path,
            modifier = Modifier.weight(1f).testTag(Tags.OUTPUT_PATH),
            style = StenoTheme.type.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        StenoIcon(R.drawable.ic_chevron_right, colors.onSurfaceVariant, size = StenoSize.iconSmall)
    }
}

/** Сбой экспорта. Результат на месте, и карточка говорит об этом первой. */
@Composable
internal fun ExportFailure(modifier: Modifier = Modifier) {
    StatusBanner(
        tone = BannerTone.ERROR,
        icon = R.drawable.ic_error,
        modifier = modifier,
        title = stringResource(R.string.scr04_export_failed_title),
        body = stringResource(R.string.scr04_export_failed_body),
        tag = Tags.EXPORT_ERROR,
        polite = true,
    )
}

/** Вариант экспорта: карточка с заголовком, пояснением и переключателем. */
@Composable
private fun ExportOption(
    title: String,
    subtitle: String,
    @DrawableRes icon: Int,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    badge: String? = null,
    onSelect: () -> Unit,
) {
    val colors = StenoTheme.colors
    val shape = RoundedCornerShape(StenoRadius.card)
    val contentAlpha = if (enabled) 1f else DISABLED_ALPHA
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.actionHeight)
            .background(if (selected) colors.primaryContainer else colors.surface, shape)
            .border(
                width = if (selected) SelectedBorder else StenoSize.hairline,
                color = if (selected) colors.primary else colors.outlineVariant,
                shape = shape,
            )
            .clip(shape)
            .testTag(tag)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = StenoSpace.l, vertical = StenoSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.m),
    ) {
        StenoIcon(icon, colors.primary.copy(alpha = contentAlpha), size = StenoSize.iconLarge)
        Column(Modifier.weight(1f)) {
            Text(title, style = StenoTheme.type.cardTitle, color = colors.onSurface.copy(alpha = contentAlpha))
            Text(subtitle, style = StenoTheme.type.bodySmall, color = colors.onSurfaceVariant)
        }
        if (badge != null && !LocalWindowLayout.current.largeText) {
            Text(
                text = badge,
                modifier = Modifier
                    .background(colors.surface, RoundedCornerShape(StenoRadius.s))
                    .padding(horizontal = StenoSpace.s, vertical = StenoSpace.xs),
                style = StenoTheme.type.labelSmall,
                color = colors.primary,
            )
        }
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = colors.primary, unselectedColor = colors.outline),
        )
    }
}

/**
 * Экспорт: выбор между JSON и протоколом, сохранение и отправка как разные действия. Лист живёт в
 * своём окне и закрывает собой сообщения экрана, поэтому действие сначала его закрывает. Отправить
 * можно только контрактный файл: у протокола нет опубликованного адреса.
 */
@Composable
internal fun ExportSheet(
    title: String,
    protocolReady: Boolean,
    exportFailed: Boolean,
    onDismiss: () -> Unit,
    onSave: (ExportKind) -> Unit,
    onShare: () -> Unit,
) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    var kind by rememberSaveable { mutableStateOf(ExportKind.TRANSCRIPT) }
    val transcript = kind == ExportKind.TRANSCRIPT
    StenoSheet(onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(Tags.EXPORT_SHEET)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = LocalWindowLayout.current.gutter)
                .padding(bottom = StenoSpace.l),
            verticalArrangement = Arrangement.spacedBy(StenoSpace.m),
        ) {
            SheetHeader(stringResource(R.string.scr04_export_title), onDismiss)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(StenoSpace.m)) {
                Column(verticalArrangement = Arrangement.spacedBy(StenoSpace.xs)) {
                    Text(title, style = type.bodyLarge, color = colors.onSurface)
                    Text(
                        text = stringResource(R.string.scr04_export_private),
                        style = type.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                ExportOption(
                    title = stringResource(R.string.scr04_export_transcript),
                    subtitle = stringResource(R.string.scr04_export_transcript_hint),
                    icon = R.drawable.ic_description,
                    selected = transcript,
                    enabled = true,
                    tag = Tags.EXPORT_TRANSCRIPT,
                    badge = stringResource(R.string.scr04_export_json),
                    onSelect = { kind = ExportKind.TRANSCRIPT },
                )
                ExportOption(
                    title = stringResource(R.string.scr04_export_protocol),
                    subtitle = stringResource(
                        if (protocolReady) R.string.scr04_export_protocol_hint else R.string.scr04_export_protocol_absent,
                    ),
                    icon = R.drawable.ic_assignment_ind,
                    selected = !transcript,
                    enabled = protocolReady,
                    tag = Tags.EXPORT_PROTOCOL,
                    onSelect = { kind = ExportKind.PROTOCOL },
                )
            }
            PrimaryAction(
                text = stringResource(
                    if (exportFailed && transcript) R.string.scr04_export_retry else R.string.scr04_export_save,
                ),
                onClick = {
                    onDismiss()
                    onSave(kind)
                },
                icon = R.drawable.ic_download,
                tag = Tags.EXPORT_SAVE,
            )
            OutlinedAction(
                text = stringResource(R.string.scr04_action_share),
                onClick = {
                    onDismiss()
                    onShare()
                },
                icon = R.drawable.ic_share,
                enabled = transcript,
                tag = Tags.SHARE,
            )
            if (transcript) {
                Text(
                    text = stringResource(R.string.scr04_export_note),
                    modifier = Modifier.fillMaxWidth(),
                    style = type.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
