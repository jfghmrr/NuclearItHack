package ru.stenokot.app.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.delay

private val EnterShift = 8.dp
private val BreathRest = 1.dp
private val BreathRise = 3.dp
private const val BREATH_SCALE = 0.012f
private const val SUCCESS_SCALE = 0.96f
private const val SUCCESS_ALPHA = 0.4f

/**
 * Плавная доля прогресса. Рост от одного настоящего значения к следующему идёт за
 * [StenoMotion.VALUE] миллисекунд, уменьшение (новый этап, повтор) встаёт сразу: полоса никогда не
 * показывает долю, которой не было. Возвращает состояние, чтобы читать его на этапе отрисовки.
 * Масштаб анимаций системы Compose учитывает сам, при нуле значение встаёт мгновенно.
 */
@Composable
fun animatedFraction(target: Float): State<Float> {
    val value = remember { Animatable(target) }
    LaunchedEffect(target) {
        if (target < value.value) {
            value.snapTo(target)
        } else {
            value.animateTo(target, tween(StenoMotion.VALUE, easing = FastOutSlowInEasing))
        }
    }
    return value.asState()
}

/** Однократное движение состояния. Значения взяты из `design_tokens.json`. */
enum class EnterMotion { EMPTY, SUCCESS, ERROR }

/**
 * Проигрывает появление один раз за жизнь экрана. Значение читается на этапе отрисовки слоя, поэтому
 * экран не перестраивается. При выключенном движении элемент сразу стоит на месте.
 */
@Composable
fun Modifier.enterOnce(motion: EnterMotion): Modifier {
    if (!LocalMotionEnabled.current) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val duration = when (motion) {
            EnterMotion.EMPTY -> StenoMotion.ENTER
            EnterMotion.SUCCESS -> StenoMotion.SUCCESS
            EnterMotion.ERROR -> StenoMotion.ERROR
        }
        progress.animateTo(1f, tween(duration, easing = FastOutSlowInEasing))
    }
    return graphicsLayer {
        val value = progress.value
        when (motion) {
            EnterMotion.EMPTY -> {
                alpha = value
                translationY = EnterShift.toPx() * (1f - value)
            }
            EnterMotion.SUCCESS -> {
                alpha = SUCCESS_ALPHA + (1f - SUCCESS_ALPHA) * value
                val scale = SUCCESS_SCALE + (1f - SUCCESS_SCALE) * value
                scaleX = scale
                scaleY = scale
            }
            EnterMotion.ERROR -> alpha = value
        }
    }
}

/**
 * Дыхание маскота: подъём на три dp за [StenoMotion.BREATH] миллисекунд. Идёт только пока экран виден
 * и движение разрешено. Значение меняется редким таймером, а не каждый кадр: отрисовка не должна
 * отнимать процессор у распознавания, а строка состояния UiAutomator остаётся свободной.
 */
@Composable
fun Modifier.breathing(): Modifier {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (!LocalMotionEnabled.current || !lifecycle.isAtLeast(Lifecycle.State.STARTED)) return this
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var elapsed = 0L
        while (true) {
            delay(StenoMotion.BREATH_STEP)
            elapsed = (elapsed + StenoMotion.BREATH_STEP) % StenoMotion.BREATH
            phase = elapsed.toFloat() / StenoMotion.BREATH
        }
    }
    return graphicsLayer {
        val breath = (1f - cos(2f * PI.toFloat() * phase)) / 2f
        translationY = (BreathRest - BreathRise * breath).toPx()
        scaleY = 1f + BREATH_SCALE * breath
        transformOrigin = TransformOrigin(0.5f, 1f)
    }
}
