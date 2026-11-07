package ru.stenokot.core.contract

import java.io.File
import kotlin.system.exitProcess

/**
 * Проверка готового файла тем же кодом, что работает в приложении. Аргументы: путь к файлу и
 * длительность записи в миллисекундах. Код возврата 0 означает, что файл соответствует контракту.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "Нужны путь к файлу и длительность в миллисекундах" }
    val bytes = File(args[0]).readBytes()
    when (val verdict = DefaultTranscriptValidator().validate(bytes, args[1].toLong())) {
        ValidationResult.Ok -> println("OK ${bytes.size} байт")
        is ValidationResult.Invalid -> {
            verdict.errors.forEach(::println)
            exitProcess(1)
        }
    }
}
