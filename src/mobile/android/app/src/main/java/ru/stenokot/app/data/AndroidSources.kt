package ru.stenokot.app.data

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.stenokot.app.pipeline.EngineFailure
import ru.stenokot.app.pipeline.SourceGateway
import ru.stenokot.app.pipeline.SourceInfo
import ru.stenokot.core.model.ErrorCode
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** Чтение импортируемого файла: адрес content:// от системы или путь file:// из adb. */
class AndroidSources(
    context: Context,
    private val workDir: () -> File,
    private val recordings: () -> File,
) : SourceGateway {
    private val app = context.applicationContext

    override suspend fun describe(job: JobRecord): SourceInfo {
        val uri = Uri.parse(job.sourceUri)
        return if (isFile(uri)) describeFile(uri) else describeContent(uri)
    }

    override suspend fun copy(job: JobRecord, target: File, progress: (Long, Long) -> Unit) {
        val uri = Uri.parse(job.sourceUri)
        val total = job.preflight?.sizeBytes ?: 0L
        val context = currentCoroutineContext()
        guarded {
            open(uri).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var copied = 0L
                    while (true) {
                        context.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        progress(copied, total)
                    }
                    output.fd.sync()
                }
            }
        }
    }

    override fun freeBytes(): Long {
        val dir = workDir().apply { mkdirs() }
        val storage = app.getSystemService(StorageManager::class.java)
        return try {
            storage.getAllocatableBytes(storage.getUuidForPath(dir))
        } catch (failure: IOException) {
            dir.usableSpace
        }
    }

    /** Копия уже у задачи, а вторая копия длинной записи заняла бы столько же места. */
    override fun release(job: JobRecord) {
        val uri = Uri.parse(job.sourceUri)
        val file = uri.path?.takeIf { isFile(uri) }?.let(::File) ?: return
        if (file.parentFile == recordings()) file.delete()
    }

    private fun describeFile(uri: Uri): SourceInfo {
        val file = File(checkNotNull(uri.path) { "Адрес без пути" })
        if (!file.isFile || !file.canRead()) {
            val code = if (audioPermissionGranted()) ErrorCode.IMPORT_READ_FAILED else ErrorCode.IMPORT_NO_PERMISSION
            throw EngineFailure(code, "Файл недоступен по пути")
        }
        return SourceInfo(file.name, file.length(), null)
    }

    private fun describeContent(uri: Uri): SourceInfo = guarded {
        keepAccess(uri)
        val resolver = app.contentResolver
        var name: String? = null
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { row ->
            if (row.moveToFirst()) {
                name = row.getString(0)
                if (!row.isNull(1)) size = row.getLong(1)
            }
        }
        if (size <= 0) {
            size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.coerceAtLeast(0) } ?: 0
        } else {
            resolver.openAssetFileDescriptor(uri, "r")?.close()
        }
        SourceInfo(name ?: uri.lastPathSegment.orEmpty(), size, resolver.getType(uri))
    }

    /** Постоянный доступ есть только у адресов из системного выбора файла, остальные его не дают. */
    private fun keepAccess(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun open(uri: Uri): InputStream =
        if (isFile(uri)) {
            FileInputStream(checkNotNull(uri.path))
        } else {
            app.contentResolver.openInputStream(uri)
                ?: throw EngineFailure(ErrorCode.IMPORT_READ_FAILED, "Источник не открылся")
        }

    private fun isFile(uri: Uri): Boolean =
        uri.scheme == null || uri.scheme == ContentResolver.SCHEME_FILE

    private fun audioPermissionGranted(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (failure: SecurityException) {
        throw EngineFailure(ErrorCode.IMPORT_NO_PERMISSION, "Нет доступа к файлу", failure)
    } catch (failure: FileNotFoundException) {
        throw EngineFailure(ErrorCode.IMPORT_READ_FAILED, "Файл не найден", failure)
    } catch (failure: IOException) {
        throw EngineFailure(ErrorCode.IMPORT_READ_FAILED, "Файл не прочитан", failure)
    }

    private companion object {
        const val BUFFER_BYTES = 1 shl 20
    }
}
