package ru.stenokot.app.llm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.stenokot.core.model.LlmBudget

/**
 * Проверка всего пути через службу модели. Foreground, как настоящий конвейер: без этого система
 * замораживает приложение в фоне вместе с процессом :llm. Команды: run запускает стадию над
 * каталогом jobs/probe, cancel отменяет её, stop завершает проверку. Итог пишется в probe_result.txt.
 */
class LlmRunnerProbeService : Service() {
    private val runner by lazy { RemoteLlmRunner(this) }
    private val scope = CoroutineScope(Dispatchers.Default)
    private val jobDir by lazy { File(getExternalFilesDir(null), "jobs/probe").apply { mkdirs() } }
    private var cancelledAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground()
        when (intent?.getStringExtra("command")) {
            "run" -> run(intent.getLongExtra("budget", 90_000L))
            "cancel" -> cancel()
            "stop" -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun enterForeground() {
        val channel = NotificationChannel(CHANNEL, "Проверка модели", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Проверка модели")
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun run(budgetMs: Long) {
        val budget = LlmBudget(nCtx = 1024, maxChunkTokens = 450, deadlineMs = budgetMs)
        val started = SystemClock.elapsedRealtime()
        cancelledAt = 0L
        scope.launch {
            val result = runner.run(jobDir, budget) { fraction, _ -> Log.i(TAG, "progress $fraction") }
            val now = SystemClock.elapsedRealtime()
            val sinceCancel = if (cancelledAt > 0) now - cancelledAt else -1
            val line = "status=${result.status} path=${result.protocolPath} detail=${result.detail} " +
                "total_ms=${now - started} since_cancel_ms=$sinceCancel"
            Log.i(TAG, "result $line")
            File(jobDir, "probe_result.txt").writeText(line)
        }
    }

    private fun cancel() {
        cancelledAt = SystemClock.elapsedRealtime()
        runner.cancel()
    }

    private companion object {
        const val TAG = "StenokotProbe"
        const val CHANNEL = "probe"
        const val NOTIFICATION_ID = 7
    }
}
