package ru.stenokot.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Цветовые токены одной темы. Это единственное место с литералами цветов:
 * экраны и компоненты берут значения только через [StenoTheme.colors].
 */
@Immutable
data class StenoColors(
    val isDark: Boolean,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val outline: Color,
    val outlineVariant: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val inversePrimary: Color,
)

/** Светлая тема «Чистовик»: тёплая бумага, тёмные чернила, глубокий бирюзовый акцент. */
val LightColors = StenoColors(
    isDark = false,
    primary = Color(0xFF0F5C73),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE8F0),
    onPrimaryContainer = Color(0xFF06303D),
    secondary = Color(0xFF4A5B66),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE5EA),
    onSecondaryContainer = Color(0xFF16242C),
    tertiary = Color(0xFF8F4A12),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE2CC),
    onTertiaryContainer = Color(0xFF3A1C05),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    success = Color(0xFF1E6B3A),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFD3F0DC),
    onSuccessContainer = Color(0xFF0B2F18),
    warning = Color(0xFF7A4F00),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFE9B8),
    onWarningContainer = Color(0xFF3A2600),
    background = Color(0xFFFCFBF8),
    onBackground = Color(0xFF1B1C1A),
    surface = Color(0xFFFCFBF8),
    onSurface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFFE6E3DD),
    onSurfaceVariant = Color(0xFF4A4C48),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F5F1),
    surfaceContainer = Color(0xFFF2F0EB),
    surfaceContainerHigh = Color(0xFFECEAE4),
    surfaceContainerHighest = Color(0xFFE6E3DD),
    outline = Color(0xFF75776F),
    outlineVariant = Color(0xFFCFCDC5),
    inverseSurface = Color(0xFF30312E),
    inverseOnSurface = Color(0xFFF3F1EC),
    inversePrimary = Color(0xFF8FD0E4),
)

/** Тёмная тема: тёплый уголь вместо бумаги, те же роли токенов. */
val DarkColors = StenoColors(
    isDark = true,
    primary = Color(0xFF8FD0E4),
    onPrimary = Color(0xFF06303D),
    primaryContainer = Color(0xFF17495A),
    onPrimaryContainer = Color(0xFFCFE8F0),
    secondary = Color(0xFFB3C3CD),
    onSecondary = Color(0xFF1D2B33),
    secondaryContainer = Color(0xFF33414A),
    onSecondaryContainer = Color(0xFFDCE5EA),
    tertiary = Color(0xFFF2B885),
    onTertiary = Color(0xFF4A2600),
    tertiaryContainer = Color(0xFF5E3210),
    onTertiaryContainer = Color(0xFFFFE2CC),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF6E1C17),
    onErrorContainer = Color(0xFFF9DEDC),
    success = Color(0xFF8FD6A6),
    onSuccess = Color(0xFF0B3319),
    successContainer = Color(0xFF1F4A2D),
    onSuccessContainer = Color(0xFFD3F0DC),
    warning = Color(0xFFE9C46A),
    onWarning = Color(0xFF3A2600),
    warningContainer = Color(0xFF4F3A0C),
    onWarningContainer = Color(0xFFFFE9B8),
    background = Color(0xFF141513),
    onBackground = Color(0xFFEDEBE5),
    surface = Color(0xFF141513),
    onSurface = Color(0xFFEDEBE5),
    surfaceVariant = Color(0xFF2F302C),
    onSurfaceVariant = Color(0xFFBDBBB3),
    surfaceContainerLowest = Color(0xFF1C1D1A),
    surfaceContainerLow = Color(0xFF191A18),
    surfaceContainer = Color(0xFF1E1F1C),
    surfaceContainerHigh = Color(0xFF242522),
    surfaceContainerHighest = Color(0xFF2A2B28),
    outline = Color(0xFF90918A),
    outlineVariant = Color(0xFF3B3C38),
    inverseSurface = Color(0xFFEDEBE5),
    inverseOnSurface = Color(0xFF30312E),
    inversePrimary = Color(0xFF0F5C73),
)

/** Форма значка говорящего. Пути заданы для области 24 на 24, смещение цифры указано в dp. */
enum class SpeakerShape(val path: String, val digitOffsetDp: Int) {
    CIRCLE("M12 2a10 10 0 1 1 0 20a10 10 0 1 1 0-20z", 0),
    ROUNDED_SQUARE("M7 3h10a4 4 0 0 1 4 4v10a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4V7a4 4 0 0 1 4-4z", 0),
    DIAMOND("M12 1l11 11-11 11L1 12z", 0),
    HEXAGON("M7.5 3h9L23 12l-6.5 9h-9L1 12z", 0),
    PENTAGON("M12 1.5l10.5 8-4 12.5h-13l-4-12.5z", 1),
    ARCH("M3 22V11a9 9 0 0 1 18 0v11z", 1),
    SHIELD("M3 2h18v11a9 9 0 0 1-18 0z", -1),
    PARALLELOGRAM("M7.5 3H23l-6.5 18H1z", 0),
}

/** Оформление одного говорящего: заливка, цвет цифры и форма. */
@Immutable
data class SpeakerStyle(val fill: Color, val onFill: Color, val shape: SpeakerShape)

/**
 * Палитра одной темы: восемь цветов и восемь форм. Цвет идёт по кругу, а на каждом новом круге форма
 * сдвигается на одну, поэтому пара цвет и форма не повторяется у первых 64 говорящих. Дальше пары
 * идут заново и говорящих различает номер. Таблица готовится один раз: лента и строки только читают её.
 */
@Immutable
class SpeakerPalette(private val styles: List<SpeakerStyle>) {
    private val pairs = List(styles.size * styles.size) { slot ->
        val colour = slot % styles.size
        val round = slot / styles.size
        styles[colour].copy(shape = styles[(colour + round) % styles.size].shape)
    }

    /** Оформление говорящего с номером [number], начиная с единицы. */
    fun style(number: Int): SpeakerStyle = pairs[(number - 1).mod(pairs.size)]

    /** Все оформления палитры по порядку. */
    val all: List<SpeakerStyle> get() = styles
}

private val shapes = SpeakerShape.entries

/** Говорящие на светлом фоне: белая цифра на насыщенном цвете. */
val LightSpeakers = SpeakerPalette(
    listOf(
        Color(0xFF1457A8), Color(0xFFA8470A), Color(0xFF1E7135), Color(0xFF6B3FB5),
        Color(0xFFAD1A72), Color(0xFF00706E), Color(0xFF6C6A00), Color(0xFF4D5761),
    ).mapIndexed { i, fill -> SpeakerStyle(fill, Color(0xFFFFFFFF), shapes[i]) },
)

/** Говорящие на тёмном фоне: тёмная цифра на светлом варианте того же оттенка. */
val DarkSpeakers = SpeakerPalette(
    listOf(
        Color(0xFF9CC2F5), Color(0xFFF0A878), Color(0xFF8AD3A0), Color(0xFFC2ACF2),
        Color(0xFFF2A0CE), Color(0xFF63D2CE), Color(0xFFCDCB66), Color(0xFFB4BEC8),
    ).mapIndexed { i, fill -> SpeakerStyle(fill, Color(0xFF141513), shapes[i]) },
)

/** Шаг сетки в dp. */
object StenoSpace {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}

/** Радиусы. Большие элементы мягче мелких, чтобы иерархия читалась и формой. */
object StenoRadius {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
}

/** Размеры, общие для компонентов. */
object StenoSize {
    val touchTarget = 48.dp
    val actionHeight = 56.dp
    val hairline = 1.dp
    val progressHeight = 8.dp
    val iconSmall = 16.dp
    val iconMedium = 20.dp
    val icon = 24.dp
    val iconLarge = 40.dp
    val mascot = 120.dp
    val ledgerMargin = 58.dp
    val railGutter = 32.dp
}

/** Длительности переходов в мс. Анимация только на смену состояния. */
object StenoMotion {
    const val SHORT = 150
    const val MEDIUM = 200
    const val LONG = 250
}
