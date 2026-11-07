package ru.stenokot.app.pipeline

import ru.stenokot.core.model.SpeechSegment

/** Выбор пробных срезов для определения языка. */
object LanguageProbes {
    private val positions = listOf(0.25, 0.5, 0.75)

    /** Возвращает до трёх разных срезов из разных мест записи, в порядке следования. */
    fun pick(segments: List<SpeechSegment>): List<SpeechSegment> {
        if (segments.size <= positions.size) return segments
        return positions
            .map { position -> segments[((segments.size - 1) * position).toInt()] }
            .distinctBy { it.index }
    }
}
