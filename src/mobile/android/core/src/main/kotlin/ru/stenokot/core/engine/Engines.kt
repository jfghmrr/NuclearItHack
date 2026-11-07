package ru.stenokot.core.engine

import java.io.Closeable
import java.io.File
import ru.stenokot.core.audio.AudioInput
import ru.stenokot.core.diar.VoicePrinter
import ru.stenokot.core.model.AsrSegment
import ru.stenokot.core.model.AudioInfo
import ru.stenokot.core.model.Language
import ru.stenokot.core.model.LlmBudget
import ru.stenokot.core.model.LlmResult
import ru.stenokot.core.model.SpeakerTurn
import ru.stenokot.core.model.SpeechSegment
import ru.stenokot.core.model.Utterance
import ru.stenokot.core.storage.DiarJournalLine

/** Приёмник прогресса стадии. Значение находится в диапазоне от 0 до 1. */
fun interface Progress {
    /** Сообщает долю выполнения [fraction] и необязательное пояснение [note]. */
    fun report(fraction: Float, note: String?)
}

/** Декодирование исходного файла в рабочий WAV. */
interface AudioDecoder {
    /** Потоково пишет PCM16 16 кГц моно в [outWav]. Память не зависит от длины записи. */
    suspend fun decode(source: AudioInput, outWav: File, progress: Progress): AudioInfo
}

/** Чтение нормализованной записи 16 кГц моно. */
interface PcmReader : Closeable {
    /** Полное число отсчётов записи. */
    val totalSamples: Long

    /** Возвращает [count] отсчётов начиная с [start] в диапазоне от -1 до 1. */
    fun read(start: Long, count: Int): FloatArray
}

/** Определитель речи: находит срезы для распознавания. */
interface Vad : Closeable {
    /** Возвращает срезы записи по порядку. */
    fun segments(pcm: PcmReader, progress: Progress): List<SpeechSegment>
}

/** Определитель языка файла по пробным срезам. */
fun interface LanguageDetector {
    /** Возвращает язык файла. По умолчанию русский. */
    fun detect(samples: List<AsrSegment>): Language

    /** Хватает ли [samples] для уверенного решения. Если нет, конвейер читает ещё срезы. */
    fun settled(samples: List<AsrSegment>): Boolean = true

    /** Русская модель почти ничего не услышала там, где есть речь: нужен второй взгляд другой моделью. */
    fun needsSecondOpinion(samples: List<AsrSegment>): Boolean = false

    /** Решение по тем же срезам в записи английской модели. */
    fun secondOpinion(samples: List<AsrSegment>): Language = Language.RU
}

/** Распознаватель речи. */
interface Asr : Closeable {
    /** Распознаёт срез [chunk] и переводит время токенов на шкалу записи. */
    fun transcribe(pcm: PcmReader, chunk: SpeechSegment): AsrSegment
}

/** Диаризатор всей записи. */
interface Diarizer : Closeable {
    /** Возвращает интервалы говорящих на шкале записи. */
    fun diarize(pcm: PcmReader, progress: Progress): List<SpeakerTurn>
}

/** Уточнение говорящих по готовым словам и звуку записи. */
fun interface TurnRefiner {
    /** Возвращает новые интервалы [turns]. Слова и текст от них не зависят, меняется только авторство. */
    fun refine(
        asr: List<AsrSegment>,
        turns: List<SpeakerTurn>,
        lines: List<DiarJournalLine>,
        printer: VoicePrinter,
        progress: Progress,
    ): List<SpeakerTurn>
}

/** Слияние слов и говорящих. Никогда не меняет текст слов. */
fun interface Merger {
    /** Собирает реплики из результатов распознавания и диаризации. */
    fun merge(asr: List<AsrSegment>, turns: List<SpeakerTurn>): List<Utterance>
}

/** Запуск языковой модели. Скрывает выполнение в отдельном процессе. */
interface LlmRunner {
    /** Запускает модель над файлами каталога задачи [jobDir]. */
    suspend fun run(jobDir: File, budget: LlmBudget, progress: Progress): LlmResult

    /** Прерывает работающую модель. */
    fun cancel()
}
