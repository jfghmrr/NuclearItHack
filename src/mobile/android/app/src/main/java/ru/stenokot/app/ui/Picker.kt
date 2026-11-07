package ru.stenokot.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private val DownloadsUri: Uri =
    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")

private val AudioTypes = arrayOf("audio/*", "application/ogg", "video/mp4")
private val AnyType = arrayOf("*/*")

/** Системный выбор файлов, который открывается сразу в папке Download. */
private class PickAudio : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, DownloadsUri)
}

/**
 * Удерживает доступ к файлу после перезапуска приложения: файл из очереди может ждать своей
 * обработки дольше, чем живёт временное разрешение. Не все источники дают такое право.
 */
private fun keepAccess(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

/**
 * Возвращает запуск выбора файла. Отмена выбора ничего не меняет. На образе без системного окна
 * выбора кнопка не падает: файл по-прежнему доступен из списка аудио и через открытие из других
 * приложений.
 */
@Composable
fun rememberAudioPicker(onPicked: (List<Uri>) -> Unit): (allFiles: Boolean) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(PickAudio()) { uris ->
        uris.forEach { keepAccess(context, it) }
        if (uris.isNotEmpty()) onPicked(uris)
    }
    return remember(launcher) {
        { allFiles ->
            try {
                launcher.launch(if (allFiles) AnyType else AudioTypes)
            } catch (_: ActivityNotFoundException) {
                Unit
            }
        }
    }
}
