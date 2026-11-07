package ru.stenokot.app.speech.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import java.io.File
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import org.json.JSONObject

/**
 * Проверочный запуск стадий на устройстве, только в отладочной сборке.
 *
 * Пример:
 * adb shell am start-foreground-service -n ru.stenokot.app.audio/ru.stenokot.app.speech.debug.HarnessService
 *   --es file /sdcard/Android/data/ru.stenokot.app.audio/files/in/clip_30s.mp3 --es name clip30
 *
 * Отчёт пишется в filesDir/harness/имя.json. Процесс завершается после отчёта, поэтому VmHWM относится
 * к одному прогону.
 */
class HarnessService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val request = HarnessRequest.from(intent)
        thread(name = "harness") {
            val report = runCatching { HarnessRun(this, request).execute() }
                .getOrElse { error -> JSONObject().put("fatal", error.stackTraceToString()) }
            val directory = File(filesDir, "harness").apply { mkdirs() }
            File(directory, "${request.name}.json").writeText(report.toString(2))
            Log.i(TAG, "HARNESS DONE ${request.name}")
            stopSelf()
            exitProcess(0)
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Проверка", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Проверка стадий")
            .build()
    }

    private companion object {
        const val TAG = "StenoHarness"
        const val CHANNEL = "harness"
        const val NOTIFICATION_ID = 7
    }
}
