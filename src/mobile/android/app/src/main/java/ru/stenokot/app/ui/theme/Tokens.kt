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
    /** Круглая подложка под маскотом: тёмный скворец должен читаться и на графите. */
    val mascotPlate: Color,
    val scrim: Color,
)

/**
 * Светлая тема: белые поверхности на холодном светло-сером фоне, синий акцент. Значения из дизайна
 * «Сквор»; успех показан тем же синим, отдельного зелёного в палитре нет.
 */
val LightColors = StenoColors(
    isDark = false,
    primary = Color(0xFF245DCC),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8EFFF),
    onPrimaryContainer = Color(0xFF1F4FAD),
    secondary = Color(0xFF5D626E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEEF0F4),
    onSecondaryContainer = Color(0xFF202329),
    tertiary = Color(0xFF975444),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDD2),
    onTertiaryContainer = Color(0xFF38231D),
    error = Color(0xFFA33426),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE4DF),
    onErrorContainer = Color(0xFF7A2115),
    success = Color(0xFF245DCC),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFE8EFFF),
    onSuccessContainer = Color(0xFF1F4FAD),
    warning = Color(0xFF8A5712),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFBEFDC),
    onWarningContainer = Color(0xFF6E450D),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF202329),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF202329),
    surfaceVariant = Color(0xFFEEF0F4),
    onSurfaceVariant = Color(0xFF5D626E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F3F7),
    surfaceContainer = Color(0xFFEEF0F4),
    surfaceContainerHigh = Color(0xFFE8EBF0),
    surfaceContainerHighest = Color(0xFFE3E6ED),
    outline = Color(0xFF777E8C),
    outlineVariant = Color(0xFFD8DCE4),
    inverseSurface = Color(0xFF202329),
    inverseOnSurface = Color(0xFFF5F6F8),
    inversePrimary = Color(0xFF92B6FF),
    recording = Color(0xFFC2412D),
    mascotPlate = Color(0xFFE8EFFF),
    scrim = Color(0x52121316),
)

/** Тёмная тема: нейтральный графит, светлый текст и мягкий синий акцент. */
val DarkColors = StenoColors(
    isDark = true,
    primary = Color(0xFF92B6FF),
    onPrimary = Color(0xFF0B244A),
    primaryContainer = Color(0xFF223452),
    onPrimaryContainer = Color(0xFFB5CDFF),
    secondary = Color(0xFFB3B6BF),
    onSecondary = Color(0xFF121316),
    secondaryContainer = Color(0xFF27292D),
    onSecondaryContainer = Color(0xFFF5F6F8),
    tertiary = Color(0xFFE6B3A7),
    onTertiary = Color(0xFF38231D),
    tertiaryContainer = Color(0xFF463029),
    onTertiaryContainer = Color(0xFFFFE1D8),
    error = Color(0xFFFFB4A5),
    onError = Color(0xFF60140C),
    errorContainer = Color(0xFF3C2521),
    onErrorContainer = Color(0xFFFFDAD2),
    success = Color(0xFF92B6FF),
    onSuccess = Color(0xFF0B244A),
    successContainer = Color(0xFF223452),
    onSuccessContainer = Color(0xFFB5CDFF),
    warning = Color(0xFFE8C58D),
    onWarning = Color(0xFF3A2C14),
    warningContainer = Color(0xFF3A3124),
    onWarningContainer = Color(0xFFF2D9AE),
    background = Color(0xFF121316),
    onBackground = Color(0xFFF5F6F8),
    surface = Color(0xFF1C1D20),
    onSurface = Color(0xFFF5F6F8),
    surfaceVariant = Color(0xFF27292D),
    onSurfaceVariant = Color(0xFFB3B6BF),
    surfaceContainerLowest = Color(0xFF0D0E10),
    surfaceContainerLow = Color(0xFF17181B),
    surfaceContainer = Color(0xFF27292D),
    surfaceContainerHigh = Color(0xFF2D2F34),
    surfaceContainerHighest = Color(0xFF33353B),
    outline = Color(0xFF858B98),
    outlineVariant = Color(0xFF383B43),
    inverseSurface = Color(0xFFF5F6F8),
    inverseOnSurface = Color(0xFF121316),
    inversePrimary = Color(0xFF245DCC),
    recording = Color(0xFFFF8A75),
    mascotPlate = Color(0xFFE9E6DF),
    scrim = Color(0x99000000),
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

/** Пары заливки и цифры. Первые три из дизайна: синий, персиковый, серый; остальные продолжают ряд. */
private val SpeakerFills = listOf(
    Color(0xFFE8EFFF) to Color(0xFF245DCC),
    Color(0xFFFFDDD2) to Color(0xFF8E4A3B),
    Color(0xFFE5E5ED) to Color(0xFF4F5060),
    Color(0xFFEBE2F0) to Color(0xFF745290),
    Color(0xFFD9EDEE) to Color(0xFF1F6A70),
    Color(0xFFF5E0EA) to Color(0xFF8D4265),
    Color(0xFFEFECCF) to Color(0xFF66601C),
    Color(0xFFE2EEE4) to Color(0xFF2F6648),
)

/** Говорящие на светлом фоне: линии тем же цветом, что и цифра. */
val LightSpeakers = SpeakerPalette(
    SpeakerFills.mapIndexed { i, (fill, ink) -> SpeakerStyle(fill, ink, shapes[i], ink) },
)

/** Говорящие на графите: тёмная заливка значка, светлая цифра и светлые линии того же тона. */
val DarkSpeakers = SpeakerPalette(
    listOf(
        Triple(Color(0xFF223452), Color(0xFFB5CDFF), Color(0xFF92B6FF)),
        Triple(Color(0xFF463029), Color(0xFFFFD2C6), Color(0xFFE6B3A7)),
        Triple(Color(0xFF37383F), Color(0xFFE5E5ED), Color(0xFFC6C6CE)),
        Triple(Color(0xFF3A2F4D), Color(0xFFDCCBFA), Color(0xFFC2ACF2)),
        Triple(Color(0xFF1E3B3D), Color(0xFF9EE6E2), Color(0xFF63D2CE)),
        Triple(Color(0xFF47283A), Color(0xFFF8C2DF), Color(0xFFF2A0CE)),
        Triple(Color(0xFF3A3920), Color(0xFFE3E19A), Color(0xFFCDCB66)),
        Triple(Color(0xFF25382E), Color(0xFFBFE3CC), Color(0xFFA2D3B5)),
    ).mapIndexed { i, (fill, ink, line) -> SpeakerStyle(fill, ink, shapes[i], line) },
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

/** Радиусы дизайна: 8, 12, 16, 20, 28. Семантические имена удобнее чисел: кнопка, карточка, поле, лист. */
object StenoRadius {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 28.dp

    /** Значок в карточке записи. */
    val tile = 12.dp

    /** Поле поиска, фильтр, информационный блок. */
    val field = 16.dp

    /** Кнопка действия. */
    val button = 16.dp

    /** Карточка содержимого. */
    val card = 20.dp

    /** Карточка записи в списке. */
    val record = 20.dp

    /** Карточка текущего этапа. */
    val stage = 20.dp

    /** Диалог подтверждения. */
    val dialog = 28.dp

    /** Нижний лист. */
    val sheet = 28.dp
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
    val mascotPlate = 128.dp
    val mascotPlateSmall = 92.dp
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

    /** Переход между двумя настоящими значениями прогресса или счётчика. */
    const val VALUE = 600

    /** Появление галочки завершённого этапа. */
    const val CHECK = 320

    /** Шаг дыхания. Сдвиг в три dp не требует шестидесяти кадров, а конвейеру нужен процессор. */
    const val BREATH_STEP = 100L
}
