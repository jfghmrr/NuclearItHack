package ru.stenokot.app.speech

import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Число потоков тяжёлых стадий. Один поток всегда остаётся интерфейсу и системе. */
object EngineThreads {
    private const val MAX_THREADS = 4
    private const val MIN_THREADS = 2
    private const val DIARIZATION_THREADS = 2

    /** Потоки распознавания: max(2, min(4, производительные ядра, ядра - 1)). */
    fun asr(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return max(MIN_THREADS, min(MAX_THREADS, min(performanceCores(cores), cores - 1)))
    }

    /** Потоки диаризации: для этих маленьких моделей больше двух почти не даёт прироста. */
    fun diarization(): Int = DIARIZATION_THREADS

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
