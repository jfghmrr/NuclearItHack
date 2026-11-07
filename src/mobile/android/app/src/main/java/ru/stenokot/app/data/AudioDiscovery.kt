package ru.stenokot.app.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import ru.stenokot.app.domain.AudioFileUi
import java.io.File

/**
 * Список аудио для главного экрана. Папка `inbox` приложения доступна без разрешений, что важно
 * для автоматики: `adb push` туда сразу виден. Общие папки появляются только при выданном разрешении,
 * и само разрешение здесь никогда не запрашивается.
 */
class AudioDiscovery(context: Context) {
    private val app = context.applicationContext

    /** Выдано ли разрешение на чтение аудио устройства. */
    fun permissionGranted(): Boolean =
        app.checkSelfPermission(readPermission()) == PackageManager.PERMISSION_GRANTED

    /** Читает файлы. Вызывается только с фонового потока. */
    fun scan(): List<AudioFileUi> {
        val found = LinkedHashMap<String, AudioFileUi>()
        app.getExternalFilesDir(INBOX_DIR)?.let { inbox -> listDir(inbox, INBOX_LABEL, found) }
        if (permissionGranted()) {
            mediaStoreFiles(found)
            for (folder in PUBLIC_FOLDERS) {
                listDir(Environment.getExternalStoragePublicDirectory(folder), folder, found)
            }
        }
        return found.values.sortedWith(compareByDescending<AudioFileUi> { it.folder == INBOX_LABEL }.thenBy { it.name })
    }

    /** Индекс системы знает длительность, поэтому его записи важнее записей из обхода папок. */
    private fun mediaStoreFiles(found: MutableMap<String, AudioFileUi>) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val columns = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.RELATIVE_PATH,
        )
        app.contentResolver.query(collection, columns, null, null, null)?.use { row ->
            while (row.moveToNext()) {
                val relative = row.getString(4).orEmpty()
                val folder = PUBLIC_FOLDERS.firstOrNull { relative.startsWith(it) } ?: continue
                val name = row.getString(1) ?: continue
                val uri = ContentUris.withAppendedId(collection, row.getLong(0))
                val duration = row.getLong(2).takeIf { it > 0 }
                found[keyOf(folder, name)] = AudioFileUi(uri.toString(), name, duration, row.getLong(3), folder)
            }
        }
    }

    private fun listDir(dir: File, label: String, found: MutableMap<String, AudioFileUi>) {
        val files = dir.listFiles { file -> file.isFile && file.extension.lowercase() in AUDIO_EXTENSIONS }
        files.orEmpty().forEach { file ->
            found.getOrPut(keyOf(label, file.name)) {
                AudioFileUi(Uri.fromFile(file).toString(), file.name, null, file.length(), label)
            }
        }
    }

    private fun keyOf(folder: String, name: String): String = "$folder/$name"

    private fun readPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private companion object {
        const val INBOX_DIR = "inbox"
        const val INBOX_LABEL = "Папка приложения"
        val AUDIO_EXTENSIONS = setOf("mp3", "wav", "m4a", "ogg", "oga", "opus", "flac", "aac", "webm", "amr")
        val PUBLIC_FOLDERS = listOf(
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_RECORDINGS,
        )
    }
}
