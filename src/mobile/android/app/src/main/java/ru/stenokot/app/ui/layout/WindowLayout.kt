package ru.stenokot.app.ui.layout

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Максимальная ширина колонки текста на средних и широких окнах. */
val ContentMaxWidth = 640.dp

private const val SHORT_HEIGHT_DP = 640
private const val WIDE_GUTTER_DP = 380

/** Как экран должен раскладываться в текущем окне. */
@Immutable
data class WindowLayout(
    val width: WindowWidthSizeClass,
    val height: WindowHeightSizeClass,
    val heightDp: Int,
    val fontScale: Float,
    val widthDp: Int = WIDE_GUTTER_DP,
) {
    /** Широкое окно или телефон в альбомной ориентации: две панели рядом. */
    val twoPane: Boolean
        get() = width == WindowWidthSizeClass.Expanded ||
            (height == WindowHeightSizeClass.Compact && width != WindowWidthSizeClass.Compact)

    /** Узкое окно: одна колонка на всю ширину. */
    val compactWidth: Boolean get() = width == WindowWidthSizeClass.Compact

    /** Низкое окно: каждую строку высоты надо беречь. */
    val compactHeight: Boolean get() = height == WindowHeightSizeClass.Compact

    /** Короткий экран: маленький телефон или альбомная ориентация. Второстепенное уходит вниз списка. */
    val short: Boolean get() = heightDp < SHORT_HEIGHT_DP

    /** Крупный шрифт системы: колонки складываются в одну, поле с таймкодом уходит в текст. */
    val largeText: Boolean get() = fontScale >= 1.5f

    /** Крупный шрифт на коротком экране: закреплённым остаётся только самое необходимое. */
    val tiny: Boolean get() = short && largeText

    /** Подсказки к импорту не помещаются рядом с кнопкой и живут в прокручиваемой части. */
    val hintsInContent: Boolean get() = short || largeText

    /** Боковое поле экрана: как на макете, а на узком телефоне меньше, чтобы сберечь строку. */
    val gutter: Dp get() = if (widthDp < WIDE_GUTTER_DP) 16.dp else 24.dp
}

/** Текущая раскладка окна. */
val LocalWindowLayout = staticCompositionLocalOf<WindowLayout> {
    error("Раскладка задаётся в ProvideWindowLayout")
}

/** Считает раскладку по размеру окна и передаёт её вниз по дереву. */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun ProvideWindowLayout(activity: Activity, content: @Composable () -> Unit) {
    val sizeClass = calculateWindowSizeClass(activity)
    val fontScale = LocalDensity.current.fontScale
    val heightDp = LocalConfiguration.current.screenHeightDp
    val widthDp = LocalConfiguration.current.screenWidthDp
    val layout = remember(sizeClass, heightDp, widthDp, fontScale) {
        WindowLayout(sizeClass.widthSizeClass, sizeClass.heightSizeClass, heightDp, fontScale, widthDp)
    }
    CompositionLocalProvider(LocalWindowLayout provides layout, content = content)
}

/** Верх и бока от системных панелей и выреза. Применяется в корне, экраны об этом не думают. */
@Composable
fun Modifier.screenInsets(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))

/** Низ и бока окна. Включает клавиатуру, поэтому строка состояния остаётся над ней. */
@Composable
fun Modifier.statusLineInsets(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))

/** Колонка по центру окна с ограниченной шириной и боковым полем по [LocalWindowLayout]. */
@Composable
fun CenteredContent(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(), content = content)
    }
}
