package ru.stenokot.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.stenokot.app.R
import ru.stenokot.app.StenoApplication
import ru.stenokot.app.domain.RecordingUi
import ru.stenokot.app.pipeline.MicRecorder

/**
 * Держит запись при выключенном экране и в фоне: без службы типа microphone система отдаёт приложению
 * тишину. Сама запись живёт в [MicRecorder], служба только запускает её после выхода на передний план
 * и уходит, когда запись закончена. START_NOT_STICKY намеренный: после смерти процесса система не
 * разрешит открыть микрофон из фона, а оборванный файл подберётся при следующем запуске приложения.
 */
class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var recorder: MicRecorder
    private var wakeLock: PowerManager.WakeLock? = null
    private var observing = false

    override fun onCreate() {
        super.onCreate()
        recorder = (application as StenoApplication).graph.recorder
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            recorder.stop()
            if (!observing) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!promote()) {
            recorder.fail()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        recorder.begin()
        if (!observing) {
            observing = true
            acquireWakeLock()
            observe()
        }
        return START_NOT_STICKY
    }

    /** Система могла остановить службу сама: файл закрывается штатно, запись не теряется. */
    override fun onDestroy() {
        recorder.stop()
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    /** Вызов напрямую, как в [PipelineService]: совместимая обёртка обрезает типы служб новых версий. */
    private fun promote(): Boolean = try {
        startForeground(NOTIFICATION_ID, notification(0, micBusy = false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        true
    } catch (failure: IllegalStateException) {
        Log.e(LOG_TAG, "Служба записи не вышла на передний план", failure)
        false
    } catch (failure: SecurityException) {
        Log.e(LOG_TAG, "Служба записи без разрешения", failure)
        false
    }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun observe() {
        val manager = getSystemService(NotificationManager::class.java)
        scope.launch {
            var shownBusy = false
            recorder.state.collect { state ->
                when (state) {
                    RecordingUi.Starting -> Unit
                    is RecordingUi.Active -> if (state.micBusy != shownBusy) {
                        shownBusy = state.micBusy
                        manager.notify(NOTIFICATION_ID, notification(state.elapsedMs, state.micBusy))
                    }
                    else -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Время идёт в самом уведомлении, без обновлений раз в секунду. Пока микрофон занят, часы стоят. */
    private fun notification(elapsedMs: Long, micBusy: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.rec_notification_title))
            .setContentText(getString(if (micBusy) R.string.rec_busy_title else R.string.rec_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setUsesChronometer(!micBusy)
            .setShowWhen(!micBusy)
            .setWhen(System.currentTimeMillis() - elapsedMs)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.rec_notification_stop), stopIntent())
            .build()

    private fun openApp(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_STOP,
        Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val LOG_TAG = "RecordingService"
        private const val ACTION_STOP = "ru.stenokot.app.action.STOP_RECORDING"
        private const val CHANNEL_ID = "recording"
        private const val CHANNEL_NAME = "Запись с микрофона"
        private const val NOTIFICATION_ID = 2
        private const val REQUEST_OPEN = 11
        private const val REQUEST_STOP = 12
        private const val WAKE_LOCK_TAG = "stenokot:recording"
        private const val WAKE_LOCK_TIMEOUT_MS = 40L * 60 * 60 * 1000

        /** Запускает службу. Вызывается при видимом приложении. Значение false означает отказ системы. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
            true
        } catch (failure: IllegalStateException) {
            Log.w(LOG_TAG, "Система не разрешила запуск службы записи", failure)
            false
        }
    }
}
