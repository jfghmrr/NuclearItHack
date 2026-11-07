package ru.stenokot.app.domain

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

/** Единственная связь интерфейса с конвейером. Вызовы возвращаются сразу, работа идёт в службе. */
interface StenoController {
    /** Состояние главного экрана. */
    val home: StateFlow<HomeUi>

    /** Доказательства работы для экрана проверки. */
    val verification: StateFlow<VerificationUi>

    /** Подробности задачи [id]. Значение null означает, что задачи нет. */
    fun job(id: String): StateFlow<JobDetailUi?>

    /** Ставит файлы в очередь и возвращает номер первой задачи. Новый импорт прерывает языковую модель. */
    fun import(uris: List<Uri>): String?

    /** Запускает задачу. Повторный вызов во время работы ничего не меняет. */
    fun start(id: String)

    /** Отменяет задачу на ближайшей безопасной границе. */
    fun cancel(id: String)

    /** Продолжает прерванную задачу с последнего чекпойнта. */
    fun resume(id: String)

    /** Удаляет задачу. Опубликованный файл удаляется только при [removeExported]. */
    fun delete(id: String, removeExported: Boolean)

    /** Сохраняет выбранный файл задачи по адресу, полученному от системы. */
    fun export(id: String, kind: ExportKind, target: Uri)

    /** Перечитывает список аудио на устройстве. */
    fun refreshAudio()

    /** Возвращает диагностический пакет без аудио и текста реплик. */
    fun diagnosticsPackage(id: String?): String
}
