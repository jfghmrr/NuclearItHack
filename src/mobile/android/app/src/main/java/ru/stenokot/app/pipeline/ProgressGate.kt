package ru.stenokot.app.pipeline

/** Пропускает обновление прогресса не чаще одного раза за [intervalMs]. */
class ProgressGate(private val intervalMs: Long = 1_000, private val clock: () -> Long) {
    private var last = Long.MIN_VALUE

    /** Возвращает true, если обновление можно показать. Вызов с [force] пропускается всегда. */
    fun allow(force: Boolean = false): Boolean {
        val now = clock()
        if (!force && last != Long.MIN_VALUE && now - last < intervalMs) return false
        last = now
        return true
    }
}
