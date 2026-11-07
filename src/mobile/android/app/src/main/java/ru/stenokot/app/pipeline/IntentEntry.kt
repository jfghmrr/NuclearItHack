package ru.stenokot.app.pipeline

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import ru.stenokot.app.domain.StenoController

/**
 * Приём файлов из VIEW, SEND и SEND_MULTIPLE, в том числе путей file:// из `adb shell am start`.
 * Активность вызывает [onCreate], [onNewIntent] и [onSaveInstanceState]. Результат вызова это номер
 * первой задачи, на экран которой надо перейти.
 */
class IntentEntry(private val controller: StenoController) {
    /** Обрабатывает запуск активности. Пересоздание и открытие из недавних файл не повторяют. */
    fun onCreate(intent: Intent?, savedInstanceState: Bundle?): String? {
        val restored = savedInstanceState?.getBoolean(HANDLED_KEY, false) ?: false
        val fromHistory = (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        return if (IntentRules.shouldImport(restored, fromHistory)) importFrom(intent) else null
    }

    /** Новый файл при живой активности всегда обрабатывается: повтор того же файла законен. */
    fun onNewIntent(intent: Intent?): String? = importFrom(intent)

    /** Запоминает, что файл текущего запуска уже принят. */
    fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(HANDLED_KEY, true)
    }

    private fun importFrom(intent: Intent?): String? = controller.import(uris(intent))

    private companion object {
        const val HANDLED_KEY = "stenokot.intent.handled"

        fun uris(intent: Intent?): List<Uri> = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(stream(intent) ?: intent.data)
            Intent.ACTION_SEND_MULTIPLE -> streams(intent)
            else -> emptyList()
        }

        fun stream(intent: Intent): Uri? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }

        fun streams(intent: Intent): List<Uri> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            }
    }
}

/** Решение о приёме файла отделено от Android, чтобы его можно было проверить на JVM. */
object IntentRules {
    /** Файл принимается один раз: восстановленная активность и запуск из истории его уже видели. */
    fun shouldImport(restored: Boolean, fromHistory: Boolean): Boolean = !restored && !fromHistory
}
