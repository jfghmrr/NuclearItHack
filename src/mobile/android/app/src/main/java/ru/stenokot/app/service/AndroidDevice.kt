package ru.stenokot.app.service

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.PowerManager
import ru.stenokot.app.pipeline.DeviceMonitor
import ru.stenokot.core.model.ErrorCode
import java.io.File

/** Показатели настоящего устройства. */
class AndroidDevice(context: Context) : DeviceMonitor {
    private val app = context.applicationContext
    private val activity = app.getSystemService(ActivityManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)

    override fun availableMemoryBytes(): Long =
        ActivityManager.MemoryInfo().also(activity::getMemoryInfo).availMem

    override fun thermalStatus(): Int = power.currentThermalStatus

    override fun peakRssKb(): Long? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmHWM") }
                ?.filter(Char::isDigit)
                ?.toLongOrNull()
        }
    }.getOrNull()

    override fun lastExitCode(): ErrorCode? =
        if (lastExitReason() == ApplicationExitInfo.REASON_LOW_MEMORY) ErrorCode.LOW_MEMORY else null

    override fun stoppedByUser(): Boolean = lastExitReason() == ApplicationExitInfo.REASON_USER_REQUESTED

    /** Система хранит причину смерти процесса с API 30. Нас интересует только основной процесс. */
    private fun lastExitReason(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            activity.getHistoricalProcessExitReasons(app.packageName, 0, EXIT_HISTORY)
        }.getOrNull().orEmpty().firstOrNull { it.processName == app.packageName }?.reason
    }

    /** Общий объём памяти устройства. */
    fun totalMemoryBytes(): Long = ActivityManager.MemoryInfo().also(activity::getMemoryInfo).totalMem

    private companion object {
        const val EXIT_HISTORY = 8
    }
}
