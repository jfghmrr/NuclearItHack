package ru.stenokot.app.pipeline

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.io.File
import ru.stenokot.app.domain.StenoController

/**
 * Приём файлов из VIEW, SEND и SEND_MULTIPLE, в том числе путей file:// из `adb shell am start`.
 * Активность вызывает [onCreate], [onNewIntent] и [onSaveInstanceState]. Результат вызова это номер
 * первой задачи, на экран которой надо перейти.
 *
 * Чужое приложение не должно заставить нас прочитать собственные закрытые файлы и выложить результат в
 * общую папку. Поэтому пути внутри [privateRoots] и адреса нашего же провайдера [ownPackage] отбрасываются.
 * Запись с микрофона в эту точку не приходит, она импортируется изнутри.
 */
class IntentEntry(
    private val controller: StenoController,
    private val privateRoots: List<String>,
    private val ownPackage: String,
) {
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

    private fun importFrom(intent: Intent?): String? {
        val accepted = uris(intent).filter { uri ->
            val path = uri.path?.let { runCatching { File(it).canonicalPath }.getOrDefault(it) }
            IntentRules.allowedSource(uri.scheme, path, uri.authority, privateRoots, ownPackage)
        }
        return if (accepted.isEmpty()) null else controller.import(accepted)
    }

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

    /**
     * Можно ли принять адрес снаружи. Путь file:// сравнивается после приведения к каноническому виду,
     * поэтому обход через `..` и ссылки не проходит. Адрес content:// нашего же провайдера чужое
     * приложение выдать не может, такой вход отбрасывается целиком.
     */
    fun allowedSource(
        scheme: String?,
        canonicalPath: String?,
        authority: String?,
        privateRoots: List<String>,
        ownPackage: String,
    ): Boolean = when (scheme) {
        null, "file" -> canonicalPath != null &&
            privateRoots.none { root -> canonicalPath == root || canonicalPath.startsWith("$root/") }
        "content" -> authority != null && authority != ownPackage && !authority.startsWith("$ownPackage.")
        else -> false
    }
}
