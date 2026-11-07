package ru.stenokot.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Цветовые токены одной темы. Это единственное место с литералами цветов:
 * экраны и компоненты берут значения только через [StenoTheme.colors].
 *
 * Соответствие токенам дизайна: `bg` это [background], `surface` это [surface] (карточки), `ink` это
 * [onSurface], `muted` это [onSurfaceVariant], `line` это [outlineVariant], `accent` это [primary],
 * `tint` это [primaryContainer], `subtle` это [surfaceContainer], `warn`, `danger` и `positive`
 * это [warning], [error] и [success].
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
    val recording: Color,
    val scrim: Color,
)

/**
 * Светлая тема. Значения взяты из `design_tokens.json`. Вторичный текст на одну ступень темнее
 * макета: исходный #63736A даёт на `subtle` и `tint` меньше 4,5:1.
 */
val LightColors = StenoColors(
    isDark = false,
    primary = Color(0xFF176B5B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4EDE1),
    onPrimaryContainer = Color(0xFF176B5B),
    secondary = Color(0xFF5D6D64),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEFEEE7),
    onSecondaryContainer = Color(0xFF21352F),
    tertiary = Color(0xFF855A27),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF4EADB),
    onTertiaryContainer = Color(0xFF855A27),
    error = Color(0xFFAE4938),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9E9E3),
    onErrorContainer = Color(0xFFAE4938),
    success = Color(0xFF27674B),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFE4EDE1),
    onSuccessContainer = Color(0xFF27674B),
    warning = Color(0xFF855A27),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFF4EADB),
    onWarningContainer = Color(0xFF855A27),
    background = Color(0xFFF7F6F1),
    onBackground = Color(0xFF21352F),
    surface = Color(0xFFFFFEFA),
    onSurface = Color(0xFF21352F),
    surfaceVariant = Color(0xFFEFEEE7),
    onSurfaceVariant = Color(0xFF5D6D64),
    surfaceContainerLowest = Color(0xFFFFFEFA),
    surfaceContainerLow = Color(0xFFF7F6F1),
    surfaceContainer = Color(0xFFEFEEE7),
    surfaceContainerHigh = Color(0xFFEFEEE7),
    surfaceContainerHighest = Color(0xFFEFEEE7),
    outline = Color(0xFF75837A),
    outlineVariant = Color(0xFFDDE3D9),
    inverseSurface = Color(0xFF21352F),
    inverseOnSurface = Color(0xFFF7F6F1),
    inversePrimary = Color(0xFFA3D4B4),
    recording = Color(0xFFBD5742),
    scrim = Color(0x54142C25),
)

/** Тёмная тема: те же роли на глубоком зелёном. Значения взяты из `design_tokens.json`. */
val DarkColors = StenoColors(
    isDark = true,
    primary = Color(0xFFA3D4B4),
    onPrimary = Color(0xFF173825),
    primaryContainer = Color(0xFF2A4737),
    onPrimaryContainer = Color(0xFFA3D4B4),
    secondary = Color(0xFFB4C3B7),
    onSecondary = Color(0xFF15251F),
    secondaryContainer = Color(0xFF24372E),
    onSecondaryContainer = Color(0xFFEEF3E9),
    tertiary = Color(0xFFE8C58D),
    onTertiary = Color(0xFF3F3523),
    tertiaryContainer = Color(0xFF3F3523),
    onTertiaryContainer = Color(0xFFE8C58D),
    error = Color(0xFFF6B5A2),
    onError = Color(0xFF442D27),
    errorContainer = Color(0xFF442D27),
    onErrorContainer = Color(0xFFF6B5A2),
    success = Color(0xFFA2D3B5),
    onSuccess = Color(0xFF173825),
    successContainer = Color(0xFF2A4737),
    onSuccessContainer = Color(0xFFA2D3B5),
    warning = Color(0xFFE8C58D),
    onWarning = Color(0xFF3F3523),
    warningContainer = Color(0xFF3F3523),
    onWarningContainer = Color(0xFFE8C58D),
    background = Color(0xFF15251F),
    onBackground = Color(0xFFEEF3E9),
    surface = Color(0xFF1E3028),
    onSurface = Color(0xFFEEF3E9),
    surfaceVariant = Color(0xFF24372E),
    onSurfaceVariant = Color(0xFFB4C3B7),
    surfaceContainerLowest = Color(0xFF1E3028),
    surfaceContainerLow = Color(0xFF15251F),
    surfaceContainer = Color(0xFF24372E),
    surfaceContainerHigh = Color(0xFF24372E),
    surfaceContainerHighest = Color(0xFF24372E),
    outline = Color(0xFF7F9184),
    outlineVariant = Color(0xFF374A3F),
    inverseSurface = Color(0xFFEEF3E9),
    inverseOnSurface = Color(0xFF15251F),
    inversePrimary = Color(0xFF176B5B),
    recording = Color(0xFFBD5742),
    scrim = Color(0x8C0A1410),
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

/**
 * Оформление одного говорящего: бледная заливка значка, цвет цифры, форма и насыщенный цвет [line]
 * для отметок на ленте, кромки активной реплики и линий протокола.
 */
@Immutable
data class SpeakerStyle(val fill: Color, val onFill: Color, val shape: SpeakerShape, val line: Color)

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

/** Пары заливки и цифры из макета. Первые пять заданы дизайном, остальные продолжают тот же ряд. */
private val SpeakerFills = listOf(
    Color(0xFFE4EDE1) to Color(0xFF236849),
    Color(0xFFF1E1D1) to Color(0xFF8D4A28),
    Color(0xFFEBE2F0) to Color(0xFF745290),
    Color(0xFFDFEAF5) to Color(0xFF355E84),
    Color(0xFFF5E0EA) to Color(0xFF8D4265),
    Color(0xFFD9EDEE) to Color(0xFF1F6A70),
    Color(0xFFEFECCF) to Color(0xFF66601C),
    Color(0xFFE6E9ED) to Color(0xFF56606C),
)

/** Говорящие на светлом фоне: линии тем же цветом, что и цифра. */
val LightSpeakers = SpeakerPalette(
    SpeakerFills.mapIndexed { i, (fill, ink) -> SpeakerStyle(fill, ink, shapes[i], ink) },
)

/** Говорящие на тёмном фоне: значок тот же, линии светлее, чтобы читаться на тёмной поверхности. */
val DarkSpeakers = SpeakerPalette(
    listOf(
        Color(0xFFA2D3B5), Color(0xFFF0A878), Color(0xFFC2ACF2), Color(0xFF9CC2F5),
        Color(0xFFF2A0CE), Color(0xFF63D2CE), Color(0xFFCDCB66), Color(0xFFB4BEC8),
    ).mapIndexed { i, line ->
        val (fill, ink) = SpeakerFills[i]
        SpeakerStyle(fill, ink, shapes[i], line)
    },
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

/** Радиусы из макета. Семантические имена удобнее чисел: кнопка, карточка, поле, лист. */
object StenoRadius {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 14.dp
    val xl = 18.dp
    val xxl = 27.dp

    /** Значок в карточке записи. */
    val tile = 12.dp

    /** Поле поиска, фильтр, информационный блок. */
    val field = 14.dp

    /** Кнопка действия. */
    val button = 17.dp

    /** Карточка содержимого. */
    val card = 18.dp

    /** Карточка записи в списке. */
    val record = 19.dp

    /** Карточка текущего этапа. */
    val stage = 20.dp

    /** Диалог подтверждения. */
    val dialog = 24.dp

    /** Нижний лист. */
    val sheet = 27.dp
}

/** Размеры, общие для компонентов. */
object StenoSize {
    val touchTarget = 48.dp
    val actionHeight = 56.dp
    val topBarHeight = 64.dp
    val hairline = 1.dp
    val progressHeight = 4.dp
    val iconSmall = 16.dp
    val iconMedium = 20.dp
    val icon = 22.dp
    val iconLarge = 28.dp
    val tile = 40.dp
    val mascot = 120.dp
    val mascotPlate = 152.dp
    val mascotPlateSmall = 100.dp
    val ledgerMargin = 58.dp
    val railGutter = 32.dp
    val stepDot = 23.dp
}

/** Длительности переходов в мс. Анимация только на смену состояния. */
object StenoMotion {
    const val SHORT = 150
    const val MEDIUM = 200
    const val LONG = 250

    /** Появление пустого состояния. */
    const val ENTER = 240

    /** Акцент завершения. */
    const val SUCCESS = 260

    /** Появление ошибки. */
    const val ERROR = 160

    /** Дыхание маскота на экране обработки, один вдох и выдох. */
    const val BREATH = 2_400

    /** Шаг дыхания. Сдвиг в три dp не требует шестидесяти кадров, а конвейеру нужен процессор. */
    const val BREATH_STEP = 100L
}
