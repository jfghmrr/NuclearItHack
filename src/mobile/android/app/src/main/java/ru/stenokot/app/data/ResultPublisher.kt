package ru.stenokot.app.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import ru.stenokot.app.pipeline.EngineFailure
import ru.stenokot.app.pipeline.MetaKind
import ru.stenokot.app.pipeline.OutputGateway
import ru.stenokot.app.pipeline.PublishedFile
import ru.stenokot.core.model.ErrorCode
import java.io.File
import java.io.IOException

/**
 * Публикует результат в `Download/Stenokot/` через MediaStore: на API 29 и выше разрешения не нужны.
 * Запись идёт с IS_PENDING, поэтому автоматика никогда не увидит половину файла, а повторная
 * обработка заменяет свою запись и не плодит `name (1).json`. Служебные файлы лежат в `meta/`,
 * чтобы поиск `*.json` в основной папке находил только контракт.
 *
 * Файл, оставшийся от удалённой установки, заменить нельзя: у него больше нет владельца, и система
 * отказывает в чтении, записи, удалении и переименовании. Без диалога или особого разрешения результат
 * получает имя `name (1).json`, и задача сообщает об этом. Если пропал сам файл, а запись осталась,
 * имя получается точным.
 */
class ResultPublisher(context: Context) : OutputGateway {
    private val app = context.applicationContext
    private val resolver: ContentResolver get() = app.contentResolver
    private val collection: Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /**
     * Сначала основной путь, при его сбое папка приложения. Байты в обоих случаях одни и те же,
     * а путь показывается пользователю честно.
     */
    override suspend fun publishTranscript(stem: String, bytes: ByteArray): PublishedFile {
        val name = "$stem.json"
        return try {
            val written = write(ROOT_PATH, name, bytes)
            val shownName = displayName(written) ?: name
            PublishedFile(publicPath(ROOT_PATH, shownName), written.toString(), fallback = false, renamed = shownName != name)
        } catch (failure: SQLiteConstraintException) {
            Log.w(TAG, "Индекс занят записью прошлой установки", failure)
            landedInFolder(name, bytes) ?: fallback(name, bytes)
        } catch (failure: Exception) {
            Log.w(TAG, "Запись через MediaStore не удалась", failure)
            fallback(name, bytes)
        }
    }

    override suspend fun publishMeta(stem: String, kind: MetaKind, source: File) {
        write(META_PATH, "$stem.${kind.suffix}.json", source.readBytes())
    }

    override suspend fun removePublished(stem: String, uri: String?) {
        uri?.let { runCatching { resolver.delete(Uri.parse(it), null, null) } }
        for (kind in MetaKind.entries) {
            find(META_PATH, "$stem.${kind.suffix}.json")?.let { resolver.delete(it, null, null) }
        }
    }

    /**
     * Сохраняет файл по адресу из системного диалога. Режим `wt` обязателен: `w` не всегда обрезает
     * файл, и от прежнего содержимого остался бы хвост.
     */
    fun export(source: File, target: Uri) {
        try {
            val output = resolver.openOutputStream(target, "wt")
                ?: throw EngineFailure(ErrorCode.EXPORT_FAILED, "Адрес не открылся на запись")
            output.use { stream -> source.inputStream().use { it.copyTo(stream) } }
        } catch (failure: SecurityException) {
            throw EngineFailure(ErrorCode.EXPORT_FAILED, "Нет доступа к адресу", failure)
        } catch (failure: IOException) {
            throw EngineFailure(ErrorCode.EXPORT_FAILED, "Файл не записан", failure)
        }
    }

    /**
     * Запись прошлой установки остаётся в индексе и после исчезновения файла. Система уже переименовала
     * наш файл в нужное имя, но индекс обновить не смогла, и IS_PENDING не снимается. Прочитать такой
     * файл приложение не вправе, а размер виден всегда. Чужого файла здесь быть не может: занятое имя
     * система заменила бы на `name (1).json` ещё при создании записи.
     */
    private fun landedInFolder(name: String, bytes: ByteArray): PublishedFile? {
        val file = File(publicPath(ROOT_PATH, name))
        return if (file.length() == bytes.size.toLong()) PublishedFile(file.path, null, fallback = false) else null
    }

    private fun fallback(name: String, bytes: ByteArray): PublishedFile {
        val dir = app.getExternalFilesDir(FALLBACK_DIR)
            ?: throw EngineFailure(ErrorCode.EXPORT_FAILED, "Нет внешней папки приложения")
        val file = File(dir, name)
        AtomicFiles.replace(file, bytes)
        return PublishedFile(file.path, null, fallback = true)
    }

    private fun write(relativePath: String, name: String, bytes: ByteArray): Uri {
        val existing = find(relativePath, name)
        val uri = existing ?: resolver.insert(collection, newRow(relativePath, name))
            ?: throw IOException("MediaStore не создал запись")
        try {
            if (existing != null) resolver.update(uri, pending(true), null, null)
            val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Запись не открылась")
            output.use { it.write(bytes) }
            resolver.update(uri, pending(false), null, null)
        } catch (failure: Exception) {
            if (existing == null) runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
        return uri
    }

    /**
     * Ищет и незавершённые записи. Процесс мог умереть между созданием записи и снятием IS_PENDING,
     * а обычный запрос такие строки не видит, и новая запись стала бы `name (1).json`.
     */
    private fun find(relativePath: String, name: String): Uri? {
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
        val arguments = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(relativePath, name))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
        }
        val query = resolver.query(pendingAware(), arrayOf(MediaStore.MediaColumns._ID), arguments, null)
            ?: return null
        return query.use { row ->
            if (row.moveToFirst()) Uri.withAppendedPath(collection, row.getLong(0).toString()) else null
        }
    }

    @Suppress("DEPRECATION")
    private fun pendingAware(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) collection else MediaStore.setIncludePending(collection)

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { row ->
            if (row.moveToFirst()) row.getString(0) else null
        }

    private fun newRow(relativePath: String, name: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, MIME_JSON)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    private fun pending(value: Boolean) = ContentValues().apply {
        put(MediaStore.MediaColumns.IS_PENDING, if (value) 1 else 0)
    }

    private fun publicPath(relativePath: String, name: String): String =
        File(Environment.getExternalStorageDirectory(), relativePath + name).path

    private companion object {
        const val TAG = "ResultPublisher"
        const val MIME_JSON = "application/json"
        const val ROOT_PATH = "Download/Stenokot/"
        const val META_PATH = "Download/Stenokot/meta/"
        const val FALLBACK_DIR = "export"
    }
}
