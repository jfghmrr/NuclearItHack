package ru.stenokot.app.data

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Запись файлов задачи: читатель видит либо старую версию, либо новую, но никогда половину. */
object AtomicFiles {
    /** Заменяет [target] байтами [bytes] через временный файл и атомарное переименование. */
    fun replace(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        moveInto(tmp, target)
    }

    /** Заменяет [target] текстом в UTF-8. */
    fun replaceText(target: File, text: String) = replace(target, text.toByteArray(Charsets.UTF_8))

    /** Атомарно переносит готовый файл [source] на место [target]. */
    fun moveInto(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
}

/**
 * Журнал из строк JSON. Каждая запись дописывается целиком и сбрасывается на диск.
 *
 * Процесс может умереть посреди записи, поэтому при восстановлении последняя неполная или
 * нечитаемая строка отбрасывается, а файл обрезается до последней целой записи.
 */
class LineJournal(private val file: File) {
    /** Читает целые записи и убирает повреждённый хвост. [parse] возвращает null для плохой строки. */
    fun <T : Any> restore(parse: (String) -> T?): List<T> {
        if (!file.exists()) return emptyList()
        val bytes = file.readBytes()
        val items = ArrayList<T>()
        var validEnd = 0
        var start = 0
        while (start < bytes.size) {
            val newline = indexOfNewline(bytes, start)
            if (newline < 0) break
            val line = String(bytes, start, newline - start, Charsets.UTF_8)
            val item = parse(line) ?: break
            items += item
            start = newline + 1
            validEnd = start
        }
        if (validEnd < bytes.size) {
            RandomAccessFile(file, "rw").use { it.setLength(validEnd.toLong()) }
        }
        return items
    }

    /** Дописывает одну запись. Символ перевода строки внутри [line] запрещён. */
    fun append(line: String) {
        require('\n' !in line) { "Запись журнала должна занимать одну строку" }
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { out ->
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    /** Удаляет журнал. */
    fun clear() {
        file.delete()
    }

    private fun indexOfNewline(bytes: ByteArray, from: Int): Int {
        for (i in from until bytes.size) if (bytes[i] == NEWLINE) return i
        return -1
    }

    private companion object {
        const val NEWLINE = '\n'.code.toByte()
    }
}
