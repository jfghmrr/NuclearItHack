package ru.stenokot.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

private val DownloadsUri: Uri =
    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")

private val AudioTypes = arrayOf("audio/*", "application/ogg", "video/mp4")
private val AnyType = arrayOf("*/*")

/** Системный выбор файлов, который открывается сразу в папке Download. */
private class PickAudio : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, DownloadsUri)
}

/** Возвращает запуск выбора файла. Отмена выбора ничего не меняет. */
@Composable
fun rememberAudioPicker(onPicked: (List<Uri>) -> Unit): (allFiles: Boolean) -> Unit {
    val launcher = rememberLauncherForActivityResult(PickAudio()) { uris ->
        if (uris.isNotEmpty()) onPicked(uris)
    }
    return remember(launcher) {
        { allFiles -> launcher.launch(if (allFiles) AnyType else AudioTypes) }
    }
}
