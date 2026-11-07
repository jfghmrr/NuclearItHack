package ru.stenokot.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Выбор темы в настройках. По умолчанию приложение следует за системой. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private const val FILE = "appearance"
private const val KEY_THEME = "theme"
private const val KEY_MOTION = "motion"

/** Настройки оформления. Значения пишутся сразу, чтение одно и при запуске. */
@Stable
class AppearanceSettings(private val prefs: SharedPreferences) {
    /** Выбранная тема. */
    var theme: ThemeMode by mutableStateOf(readTheme())
        private set

    /** Разрешено ли декоративное движение маскота. */
    var mascotMotion: Boolean by mutableStateOf(prefs.getBoolean(KEY_MOTION, true))
        private set

    /** Меняет тему и запоминает выбор. */
    fun selectTheme(mode: ThemeMode) {
        theme = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    /** Включает или выключает движение маскота. */
    fun selectMotion(enabled: Boolean) {
        mascotMotion = enabled
        prefs.edit().putBoolean(KEY_MOTION, enabled).apply()
    }

    private fun readTheme(): ThemeMode {
        val saved = prefs.getString(KEY_THEME, null)
        return ThemeMode.entries.firstOrNull { it.name == saved } ?: ThemeMode.SYSTEM
    }
}

/** Открывает настройки оформления приложения. */
fun appearanceSettings(context: Context): AppearanceSettings =
    AppearanceSettings(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

/** Настройки оформления текущего окна. */
val LocalAppearance = staticCompositionLocalOf<AppearanceSettings> {
    error("Настройки оформления задаются в корне приложения")
}

/** Можно ли сейчас двигать декоративные элементы: системная шкала и переключатель настроек. */
val LocalMotionEnabled = staticCompositionLocalOf { false }

/** Выключено ли движение в системе. Значение перечитывается при возврате в приложение. */
@Composable
fun rememberSystemMotion(): Boolean {
    val context = LocalContext.current
    var enabled by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(systemMotion(context)) }
    LifecycleResumeEffect(context) {
        enabled = systemMotion(context)
        onPauseOrDispose {}
    }
    return enabled
}

private fun systemMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
