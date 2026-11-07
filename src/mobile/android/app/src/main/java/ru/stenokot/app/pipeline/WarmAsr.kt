package ru.stenokot.app.pipeline

import java.util.concurrent.FutureTask
import ru.stenokot.core.engine.Asr

/**
 * Распознаватель, загруженный заранее. Загрузка модели занимает секунды, а прогрев приложения в замер
 * скорости не входит, поэтому модель ждёт файл в памяти. Задача забирает её через [take] и дальше
 * владеет ею сама. Сразу после загрузки держать её некому, поэтому [prepare] проверяет свободную
 * память, а сбой загрузки молчит: задача загрузит модель обычным путём и получит настоящую ошибку.
 */
internal class WarmAsr(private val load: () -> Asr, private val hasRoom: () -> Boolean) {
    private var pending: FutureTask<Asr?>? = null

    /** Начинает загрузку в своём потоке, если модели ещё нет и памяти хватает. Вызов мгновенный. */
    @Synchronized
    fun prepare() {
        if (pending != null || !hasRoom()) return
        pending = FutureTask { runCatching(load).getOrNull() }.also { Thread(it, THREAD_NAME).start() }
    }

    /** Отдаёт модель вызывающему. Идущая загрузка дожидается конца: вторая копия в память не ляжет. */
    fun take(): Asr? = synchronized(this) { pending.also { pending = null } }?.get()

    /** Освобождает модель, которую никто не забрал. Идущая загрузка дожидается конца. */
    fun release() {
        take()?.close()
    }

    private companion object {
        const val THREAD_NAME = "asr-warm"
    }
}
