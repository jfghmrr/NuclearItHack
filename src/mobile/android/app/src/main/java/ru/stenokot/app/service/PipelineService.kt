package ru.stenokot.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.stenokot.app.R
import ru.stenokot.app.StenoApplication
import ru.stenokot.app.domain.UserStage
import ru.stenokot.app.pipeline.LiveProgress
import ru.stenokot.app.pipeline.PipelineRunner
import ru.stenokot.app.pipeline.ProgressGate
import ru.stenokot.core.model.Stage

/**
 * Держит процесс живым, пока идёт обработка. Сама работа выполняется в [PipelineRunner], поэтому
 * служба не знает о стадиях. START_NOT_STICKY намеренный: после смерти процесса задача продолжается
 * только при открытом приложении, из фона система всё равно не разрешит запуск.
 */
class PipelineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = ProgressGate(clock = System::currentTimeMillis)
    private lateinit var runner: PipelineRunner
    private var wakeLock: PowerManager.WakeLock? = null
    private var observing = false
    private var foreground = false
    private var lastStartId = 0
    private var lastStage: Stage? = null

    override fun onCreate() {
        super.onCreate()
        runner = (application as StenoApplication).graph.runner
        createChannel()
        getSystemService(NotificationManager::class.java).cancel(PAUSED_NOTIFICATION_ID)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (!foreground && !promote(baseType())) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL) runner.cancelActive()
        if (!observing) {
            observing = true
            acquireWakeLock()
            observeProgress()
            stopWhenIdle()
        }
        return START_NOT_STICKY
    }

    /**
     * Лимит типа службы на Android 15 и новее: шесть часов за сутки, пока приложение не на экране.
     * Запись на восемь часов при медленном устройстве до него доходит, поэтому служба сразу
     * продолжает работу с типом без лимита. Если система откажет, чекпойнт уже на диске: задача
     * останавливается на границе среза, а служба обязана уйти за секунды, иначе система завершит
     * процесс с ошибкой. Открытие приложения обнуляет лимит, а задача ждёт простоя или нажатия.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        foreground = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            promote(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        ) {
            Log.i(LOG_TAG, "Лимит типа $fgsType исчерпан, работа продолжается")
            return
        }
        Log.w(LOG_TAG, "Лимит типа $fgsType исчерпан, задача приостановлена")
        runner.interruptForTimeout()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        tellPaused()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        runner.onTaskRemoved()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    /**
     * Отказ разрешения на уведомления запуску не мешает: тип службы от него не зависит.
     *
     * Вызов идёт напрямую, а не через ServiceCompat: core 1.18.0 обрезает тип маской времён API 34,
     * mediaProcessing превращается в ноль, и система отказывает в запуске.
     */
    private fun promote(type: Int): Boolean = try {
        startForeground(NOTIFICATION_ID, notification(runner.live.value), type)
        foreground = true
        true
    } catch (failure: IllegalStateException) {
        Log.e(LOG_TAG, "Служба не вышла на передний план", failure)
        false
    }

    private fun baseType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

    private fun acquireWakeLock() {
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun observeProgress() {
        val manager = getSystemService(NotificationManager::class.java)
        scope.launch {
            runner.live.collect { live ->
                val stageChanged = live?.stage != lastStage
                if (gate.allow(force = stageChanged)) {
                    lastStage = live?.stage
                    manager.notify(NOTIFICATION_ID, notification(live))
                }
            }
        }
    }

    private fun stopWhenIdle() {
        scope.launch {
            var stopped = false
            while (!stopped) {
                withTimeoutOrNull(START_GRACE_MS) { runner.busy.first { it } }
                runner.busy.first { !it }
                Log.i(LOG_TAG, "Работы нет, служба останавливается")
                stopForeground(STOP_FOREGROUND_REMOVE)
                foreground = false
                stopped = stopSelfResult(lastStartId)
            }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Служба ушла, а в фоне система её не вернёт: сказать об этом может только уведомление. */
    private fun tellPaused() {
        val notice = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(APP_TITLE)
            .setContentText(PAUSED_LABEL)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        getSystemService(NotificationManager::class.java).notify(PAUSED_NOTIFICATION_ID, notice)
    }

    private fun notification(live: LiveProgress?): Notification {
        val percent = live?.fraction?.let { (it * PERCENT).toInt().coerceIn(0, PERCENT) }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(APP_TITLE)
            .setContentText(stageLabel(live))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .addAction(0, CANCEL_LABEL, cancelIntent())
        if (percent != null) builder.setProgress(PERCENT, percent, false)
        return builder.build()
    }

    private fun stageLabel(live: LiveProgress?): String {
        val stage = live?.stage ?: return IDLE_LABEL
        return when (UserStage.entries.first { stage in it.stages }) {
            UserStage.PREPARE_AUDIO -> "Подготовка аудио"
            UserStage.RECOGNIZE -> "Распознавание речи"
            UserStage.DIARIZE -> "Определение говорящих"
            UserStage.SAVE_TRANSCRIPT -> "Сохранение стенограммы"
            UserStage.BUILD_PROTOCOL -> "Формирование протокола"
        }
    }

    private fun openApp(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_CANCEL,
        Intent(this, PipelineService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val LOG_TAG = "PipelineService"
        private const val ACTION_CANCEL = "ru.stenokot.app.action.CANCEL"
        private const val CHANNEL_ID = "pipeline"
        private const val CHANNEL_NAME = "Обработка записи"
        private const val APP_TITLE = "Стенокот"
        private const val IDLE_LABEL = "Обработка записи"
        private const val CANCEL_LABEL = "Отмена"
        private const val PAUSED_LABEL = "Обработка приостановлена. Откройте приложение, чтобы продолжить"
        private const val NOTIFICATION_ID = 1
        private const val PAUSED_NOTIFICATION_ID = 2
        private const val REQUEST_OPEN = 1
        private const val REQUEST_CANCEL = 2
        private const val PERCENT = 100
        private const val START_GRACE_MS = 5_000L
        private const val WAKE_LOCK_TAG = "stenokot:pipeline"
        private const val WAKE_LOCK_TIMEOUT_MS = 24L * 60 * 60 * 1000

        /** Убирает уведомление о паузе: приложение открыто, и прерванную задачу видно на экране. */
        fun clearPausedNotice(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(PAUSED_NOTIFICATION_ID)
        }

        /**
         * Запускает службу. Вызывается при видимом приложении. Отказ системы не теряет задачу:
         * конвейер работает, пока жив процесс, а защита вернётся при следующем открытии.
         */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, PipelineService::class.java))
            } catch (failure: IllegalStateException) {
                Log.w(LOG_TAG, "Система не разрешила запуск службы", failure)
            }
        }
    }
}
