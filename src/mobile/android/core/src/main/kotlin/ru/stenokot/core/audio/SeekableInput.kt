package ru.stenokot.core.audio

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Файл, открытый на чтение с произвольной позиции. Работает от дескриптора, поэтому одинаково читает
 * копию в каталоге задачи и чужой файл за адресом content://, к которому нет прямого пути.
 */
class SeekableInput(private val stream: FileInputStream) : Closeable {
    private val channel = stream.channel

    /** Дескриптор открытого файла. */
    val descriptor: FileDescriptor get() = stream.fd

    /** Размер файла в байтах. */
    fun length(): Long = channel.size()

    /** Переходит к позиции [position] от начала файла. */
    fun seek(position: Long) {
        channel.position(position)
    }

    /** Читает с текущей позиции до [length] байт. Значение -1 означает конец файла. */
    fun read(target: ByteArray, offset: Int = 0, length: Int = target.size): Int =
        channel.read(ByteBuffer.wrap(target, offset, length))

    /** Читает с позиции [position], не сдвигая текущую. Значение -1 означает конец файла. */
    fun readAt(position: Long, target: ByteArray, offset: Int, length: Int): Int =
        channel.read(ByteBuffer.wrap(target, offset, length), position)

    /** Заполняет [target] целиком или бросает [EOFException]. */
    fun readFully(target: ByteArray) {
        var done = 0
        while (done < target.size) {
            val got = read(target, done, target.size - done)
            if (got < 0) throw EOFException()
            done += got
        }
    }

    override fun close() {
        stream.close()
    }
}

/** Запись, которую можно открыть. Каждое открытие читает независимо от остальных. */
interface AudioInput {
    /** Имя файла: по расширению видно заявленный формат. */
    val name: String

    /** Открывает запись на чтение. */
    fun open(): SeekableInput
}

/** Запись в обычном файле. */
class FileAudioInput(private val file: File) : AudioInput {
    override val name: String get() = file.name

    override fun open(): SeekableInput = SeekableInput(FileInputStream(file))
}
