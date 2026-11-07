package ru.stenokot.app.ui.result

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.stenokot.app.R
import ru.stenokot.app.domain.OutputFileUi
import ru.stenokot.app.ui.Tags
import ru.stenokot.app.ui.components.BannerTone
import ru.stenokot.app.ui.components.OutlinedAction
import ru.stenokot.app.ui.components.StatusBanner
import ru.stenokot.app.ui.components.StenoIcon
import ru.stenokot.app.ui.components.TonalAction
import ru.stenokot.app.ui.components.resourceTags
import ru.stenokot.app.ui.format.formatFileSize
import ru.stenokot.app.ui.format.formatHistoryDate
import ru.stenokot.app.ui.layout.LocalWindowLayout
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoSpace
import ru.stenokot.app.ui.theme.StenoTheme

private const val DOCS_AUTHORITY = "com.android.externalstorage.documents"
private const val PRIMARY_VOLUME = "primary:"
private const val APP_FOLDER_MARK = "Android/data/"
private const val MIME_JSON = "application/json"
private const val WRITE_POLLS = 30
private const val WRITE_POLL_MS = 100L
private const val META_SEPARATOR = " · "

/** Файл лежит в папке приложения: запись в Download не удалась. */
internal val OutputFileUi.inAppFolder: Boolean get() = APP_FOLDER_MARK in path

/** Папка файла относительно общего хранилища. */
internal fun outputFolder(path: String): String = path.substringBeforeLast('/', "")

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

/** Сводка файла под кнопками: где лежит, сколько весит, когда создан и прошёл ли проверку. */
@Composable
internal fun OutputLine(output: OutputFileUi, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = StenoTheme.colors
    val type = StenoTheme.type
    val path = stringResource(
        if (output.inAppFolder) R.string.scr04_saved_fallback else R.string.common_saved,
        output.path,
    )
    val validity = stringResource(if (output.validated) R.string.scr04_json_valid else R.string.scr04_json_unchecked)
    val meta = listOf(formatFileSize(output.sizeBytes), formatHistoryDate(output.createdAtMs), validity)
        .joinToString(META_SEPARATOR)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = StenoSize.touchTarget)
            .clickable(
                onClickLabel = stringResource(R.string.scr04_panel_open),
                role = Role.Button,
                onClick = onOpen,
            )
            .padding(horizontal = LocalWindowLayout.current.gutter, vertical = StenoSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StenoSpace.s),
    ) {
        StenoIcon(
            icon = if (output.validated) R.drawable.ic_check_circle_fill else R.drawable.ic_warning,
            tint = if (output.validated) colors.success else colors.warning,
            size = StenoSize.iconSmall,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = path,
                modifier = Modifier.testTag(Tags.OUTPUT_PATH),
                style = type.bodySmall,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(
                text = meta,
                modifier = Modifier.testTag(Tags.OUTPUT_META),
                style = type.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        StenoIcon(R.drawable.ic_chevron_right, colors.onSurfaceVariant)
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

@Composable
private fun FactRow(label: String, value: String, mono: Boolean = false) {
    val type = StenoTheme.type
    val colors = StenoTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(StenoSpace.m)) {
        Text(label, Modifier.width(88.dp), style = type.bodyMedium, color = colors.onSurfaceVariant)
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = if (mono) type.monoSmall else type.bodyMedium,
            color = colors.onSurface,
        )
    }
}

/**
 * Панель файла: путь, размер, время, проверка и действия. На крупном шрифте сюда же уходят кнопки
 * нижней панели через [extra]. Содержимое прокручивается: панель обязана помещаться в любом окне.
 * Панель живёт в своём окне и закрывает собой сообщения экрана, поэтому действие сначала её
 * закрывает, иначе подтверждение или отказ остались бы невидимыми.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExportSheet(
    output: OutputFileUi,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onCopyPath: () -> Unit,
    onOpenFolder: () -> Unit,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = StenoTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainerLow,
        contentColor = colors.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .resourceTags()
                .testTag(Tags.EXPORT_PANEL)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LocalWindowLayout.current.gutter)
                .padding(bottom = StenoSpace.l)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(StenoSpace.m),
        ) {
            Text(
                text = stringResource(R.string.scr04_panel_title),
                modifier = Modifier.semantics { heading() },
                style = StenoTheme.type.titleLarge,
                color = colors.onSurface,
            )
            FactRow(stringResource(R.string.scr04_panel_path), output.path)
            FactRow(stringResource(R.string.scr04_panel_size), formatFileSize(output.sizeBytes))
            FactRow(stringResource(R.string.scr04_panel_created), formatHistoryDate(output.createdAtMs))
            FactRow(
                stringResource(R.string.scr04_panel_validity),
                stringResource(if (output.validated) R.string.scr04_json_valid_long else R.string.scr04_json_unchecked),
            )
            output.sha256?.let { FactRow(stringResource(R.string.scr04_panel_sha), it, mono = true) }
            TonalAction(
                text = stringResource(R.string.scr04_action_share),
                onClick = { onDismiss(); onShare() },
                icon = R.drawable.ic_share,
                tag = Tags.SHARE,
            )
            OutlinedAction(
                text = stringResource(R.string.scr04_action_copy_path),
                onClick = { onDismiss(); onCopyPath() },
                icon = R.drawable.ic_content_copy,
                tag = Tags.COPY_PATH,
            )
            OutlinedAction(
                text = stringResource(R.string.scr04_action_open_folder),
                onClick = { onDismiss(); onOpenFolder() },
                icon = R.drawable.ic_folder_open,
                enabled = !output.inAppFolder,
                tag = Tags.OPEN_FOLDER,
            )
            extra?.invoke(this)
        }
    }
}
