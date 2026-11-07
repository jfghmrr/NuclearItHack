package ru.stenokot.app.speech

import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Число потоков тяжёлых стадий. Один поток всегда остаётся интерфейсу и системе. */
object EngineThreads {
    private const val MAX_THREADS = 4
    private const val MIN_THREADS = 2

    /** Потоки распознавания: max(2, min(4, производительные ядра, ядра - 1)). */
    fun asr(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return max(MIN_THREADS, min(MAX_THREADS, min(performanceCores(cores), cores - 1)))
    }

    /**
     * Потоки диаризации, та же формула. Snapdragon 662, файл 3 минуты: 2 потока 25,8 с, 3 потока 21,1 с,
     * 4 потока 19,0 с, 6 потоков 19,3 с. Пик памяти от числа потоков не зависит.
     */
    fun diarization(): Int = asr()

    /** Ядра с наибольшей частотой. На эмуляторе частот нет, тогда все ядра считаются равными. */
    private fun performanceCores(cores: Int): Int {
        val frequencies = (0 until cores).mapNotNull { index ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$index/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrNull()
        }
        if (frequencies.size < cores) return cores
        val top = frequencies.max()
        return frequencies.count { it == top }
    }
}
