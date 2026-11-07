package ru.stenokot.app.llm

import android.app.ActivityManager
import android.content.Context
import java.io.File

/** Сведения об устройстве, от которых зависит запуск модели. */
internal object LlmDevice {
    private const val BYTES_PER_MIB = 1024L * 1024L
    private const val MIN_AVAILABLE_MIB = 650L
    private const val PERFORMANCE_SHARE = 0.8

    /** Число потоков: max(2, min(4, производительные ядра, ядра - 1)). */
    fun threadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return threadCount(cores, performanceCores(cores))
    }

    /** Формула отделена от чтения устройства, чтобы её можно было проверить на компьютере. */
    fun threadCount(cores: Int, performanceCores: Int): Int =
        maxOf(2, minOf(4, performanceCores, cores - 1))

    /** Свободна ли память для запуска. Веса отображаются из файла, поэтому порог ниже пика процесса. */
    fun hasEnoughMemory(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return info.availMem >= MIN_AVAILABLE_MIB * BYTES_PER_MIB
    }

    /** На эмуляторе частоты недоступны, тогда все ядра считаются равными. */
    private fun performanceCores(cores: Int): Int {
        val frequencies = (0 until cores).mapNotNull(::maxFrequency)
        val top = frequencies.maxOrNull() ?: return cores
        return frequencies.count { it >= top * PERFORMANCE_SHARE }
    }

    private fun maxFrequency(core: Int): Long? = runCatching {
        File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
    }.getOrNull()
}
