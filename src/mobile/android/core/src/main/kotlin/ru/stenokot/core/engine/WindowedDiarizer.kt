package ru.stenokot.core.engine

import ru.stenokot.core.model.SpeakerTurn

/**
 * Итог одного окна диаризации.
 *
 * [turns] уже приведены к глобальным номерам говорящих. [state] для конвейера непрозрачен: это
 * книга говорящих после окна, она попадает в чекпойнт и возвращается при продолжении.
 */
data class DiarizationWindow(val turns: List<SpeakerTurn>, val state: String)

/**
 * Диаризатор, который конвейер ведёт окно за окном.
 *
 * Обычный [Diarizer.diarize] неделим, поэтому чекпойнт после каждого окна и отмена между окнами
 * возможны только через этот интерфейс. Реализация без него считается одним большим окном.
 */
interface WindowedDiarizer : Diarizer {
    /** Возвращает число окон для записи длиной [totalSamples]. */
    fun windowCount(totalSamples: Long): Int

    /** Считает окно [index], продолжая с состояния [state] предыдущего окна. */
    fun diarizeWindow(pcm: PcmReader, index: Int, state: String?, progress: Progress): DiarizationWindow

    /** Сводит окна в итоговый список. Здесь же выполняется постобработка после склейки. */
    fun finish(windows: List<List<SpeakerTurn>>): List<SpeakerTurn> = windows.flatten()
}
