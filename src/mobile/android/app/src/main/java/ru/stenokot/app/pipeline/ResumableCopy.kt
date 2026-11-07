package ru.stenokot.app.pipeline

import ru.stenokot.app.data.AtomicFiles
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Копия большого файла с проверкой SHA-256, которая переживает паузу и смерть процесса.
 *
 * Уже записанная часть остаётся в [tmp]. При продолжении её байты снова проходят через хэш, поэтому
 * итоговая сверка покрывает файл целиком, а не только дописанный хвост. Перед каждым блоком
 * вызывается пауза: так копия уступает диск и процессор обработке и ждёт простоя.
 */
class ResumableCopy(
    private val open: () -> InputStream,
    private val totalBytes: Long,
    private val tmp: File,
    private val target: File,
    private val expectedSha256: String?,
    private val bufferBytes: Int = DEFAULT_BUFFER,
) {
    /** Копирует файл. Возвращает false, если хэш не совпал: тогда копия удаляется. */
    suspend fun run(beforeBlock: suspend () -> Unit, onProgress: (Long) -> Unit): Boolean {
        tmp.parentFile?.mkdirs()
        if (tmp.exists() && tmp.length() > totalBytes) tmp.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferBytes)
        var copied = hashExisting(digest, buffer)
        onProgress(copied)
        open().use { input ->
            skipFully(input, copied)
            FileOutputStream(tmp, true).use { output ->
                while (true) {
                    beforeBlock()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    copied += read
                    onProgress(copied)
                }
                output.fd.sync()
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (expectedSha256 != null && !actual.equals(expectedSha256, ignoreCase = true)) {
            tmp.delete()
            return false
        }
        target.parentFile?.mkdirs()
        AtomicFiles.moveInto(tmp, target)
        return true
    }

    private fun hashExisting(digest: MessageDigest, buffer: ByteArray): Long {
        if (!tmp.exists()) return 0
        var total = 0L
        FileInputStream(tmp).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                total += read
            }
        }
        return total
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) {
                if (input.read() < 0) return
                left -= 1
            } else {
                left -= skipped
            }
        }
    }

    private companion object {
        const val DEFAULT_BUFFER = 1 shl 20
    }
}
