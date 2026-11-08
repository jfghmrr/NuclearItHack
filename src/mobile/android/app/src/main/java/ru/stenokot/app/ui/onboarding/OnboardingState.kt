package ru.stenokot.app.ui.onboarding

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

private const val FILE = "onboarding"
private const val KEY_SEEN = "seen"

/**
 * Знакомство с приложением: флаг «уже видели» в настройках и видимость на экране. Флаг пишется сразу,
 * читается один раз при создании.
 */
@Stable
class OnboardingState internal constructor(
    private val prefs: SharedPreferences,
    visible: Boolean,
) {
    /** Знакомство уже показывали или оно больше не нужно. */
    var seen: Boolean = prefs.getBoolean(KEY_SEEN, false)
        private set

    /** Знакомство сейчас на экране. */
    var visible: Boolean by mutableStateOf(visible)
        private set

    /** Применяет решение [OnboardingRules] при запуске. */
    fun follow(decision: OnboardingDecision) {
        when (decision) {
            OnboardingDecision.SHOW -> visible = true
            OnboardingDecision.HIDE -> visible = false
            OnboardingDecision.HIDE_FOR_GOOD -> finish()
        }
    }

    /** Закрывает знакомство и запоминает, что его видели. */
    fun finish() {
        visible = false
        if (!seen) {
            seen = true
            prefs.edit().putBoolean(KEY_SEEN, true).apply()
        }
    }

    /** Показывает знакомство ещё раз по просьбе человека. */
    fun replay() {
        visible = true
    }
}

private fun prefs(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

/**
 * Состояние знакомства, которое переживает поворот и пересоздание активности. Решение [decide]
 * принимается один раз до первого кадра, поэтому Главная под знакомством не мелькает.
 */
@Composable
fun rememberOnboardingState(decide: (seen: Boolean) -> OnboardingDecision): OnboardingState {
    val context = LocalContext.current
    val saver = Saver<OnboardingState, Boolean>(
        save = { it.visible },
        restore = { OnboardingState(prefs(context), it) },
    )
    return rememberSaveable(saver = saver) {
        OnboardingState(prefs(context), visible = false).also { it.follow(decide(it.seen)) }
    }
}
