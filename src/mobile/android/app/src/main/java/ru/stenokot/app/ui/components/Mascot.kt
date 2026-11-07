package ru.stenokot.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.stenokot.app.ui.theme.StenoColors
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoTheme

/** Сюжет маскота. Маскот появляется только на пустых состояниях и состояниях ошибки. */
enum class MascotKind { EMPTY, NO_SPEECH, WAITING }

private enum class Ink { NONE, LINE, FUR, CREAM, PAPER, ACCENT }

private class Layer(val d: String, val fill: Ink = Ink.NONE, val line: Ink = Ink.NONE, val width: Float = 2.2f)

private val Head = listOf(
    Layer(
        "M38 33L35 11L50 22Q60 19 70 22L85 11L82 33C87 40 87 50 82 55C75 63 45 63 38 55C33 50 33 40 38 33Z",
        Ink.FUR,
        Ink.LINE,
    ),
    Layer("M41 15L44 24M79 15L76 24", line = Ink.LINE, width = 1.6f),
    Layer("M54 23V29M60 22V29M66 23V29", line = Ink.LINE, width = 1.8f),
    Layer("M47 44Q60 38 73 44C76 52 70 58 60 58C50 58 44 52 47 44Z", Ink.CREAM),
    Layer("M58 46H62L60 48.5Z", Ink.LINE, Ink.LINE, 1.4f),
    Layer("M60 48.5Q58 52 54.5 50.5M60 48.5Q62 52 65.5 50.5", line = Ink.LINE, width = 1.6f),
    Layer("M34 45L25 43M34 50L25 51.5M86 45L95 43M86 50L95 51.5", line = Ink.LINE, width = 1.6f),
)

private val OpenEyes = listOf(
    Layer("M47.6 39a2.4 2.4 0 1 0 4.8 0a2.4 2.4 0 1 0 -4.8 0Z", Ink.LINE),
    Layer("M67.6 39a2.4 2.4 0 1 0 4.8 0a2.4 2.4 0 1 0 -4.8 0Z", Ink.LINE),
)

private val Body = Layer(
    "M43 58C34 70 30 90 30 110H90C90 90 86 70 77 58Z",
    Ink.FUR,
    Ink.LINE,
)

private val Tail = Layer(
    "M88 110C104 110 114 100 110 88C108 82 101 83 102 90C103 96 97 100 89 99Z",
    Ink.FUR,
    Ink.LINE,
)

private val Floor = Layer("M16 110H104", line = Ink.LINE)

private val Scenes: Map<MascotKind, List<Layer>> = mapOf(
    MascotKind.EMPTY to buildList {
        add(Tail)
        add(Body)
        add(Floor)
        add(Layer("M41 70H79A3 3 0 0 1 82 73V101A3 3 0 0 1 79 104H41A3 3 0 0 1 38 101V73A3 3 0 0 1 41 70Z", Ink.PAPER, Ink.LINE))
        add(Layer("M41 70H79A3 3 0 0 1 82 73V77H38V73A3 3 0 0 1 41 70Z", Ink.ACCENT, Ink.LINE))
        add(Layer("M46 73.5H46.1M53 73.5H53.1M60 73.5H60.1M67 73.5H67.1M74 73.5H74.1", line = Ink.PAPER, width = 2.4f))
        add(Layer("M32 90C37 86 43 88 44 93C44 98 38 100 34 98Z", Ink.FUR, Ink.LINE))
        add(Layer("M84 86L99 60L105 63.5L90 89.5Z", Ink.ACCENT, Ink.LINE))
        add(Layer("M84 86L90 89.5L82 95Z", Ink.CREAM, Ink.LINE))
        add(Layer("M92 92C87 87 79 89 78 94C78 99 85 102 90 99Z", Ink.FUR, Ink.LINE))
        addAll(Head)
        addAll(OpenEyes)
    },
    MascotKind.NO_SPEECH to buildList {
        add(Tail)
        add(Body)
        add(Floor)
        add(Layer("M44 84C44 94 46 104 48 110M76 84C76 94 74 104 72 110", line = Ink.LINE, width = 1.8f))
        addAll(Head)
        addAll(OpenEyes)
        add(
            Layer(
                "M94 8H110A6 6 0 0 1 116 14V26A6 6 0 0 1 110 32H100L93 38V32A6 6 0 0 1 88 26V14A6 6 0 0 1 94 8Z",
                Ink.PAPER,
                Ink.LINE,
            ),
        )
    },
    MascotKind.WAITING to listOf(
        Layer("M14 102H106A4 4 0 0 1 110 106V110H10V106A4 4 0 0 1 14 102Z", Ink.ACCENT, Ink.LINE),
        Layer("M18 106H102", line = Ink.PAPER, width = 1.6f),
        Layer("M22 102C14 80 30 54 62 54C92 54 110 76 100 102Z", Ink.FUR, Ink.LINE),
        Layer("M96 102C108 100 110 86 100 82C94 80 92 90 96 92", line = Ink.LINE),
        Layer("M28 74L27 54L41 63Q49 60 57 63L71 54L69 74C74 81 73 91 67 96C60 101 38 101 31 96C25 91 23 81 28 74Z", Ink.FUR, Ink.LINE),
        Layer("M37 87Q49 82 61 87C63 94 57 98 49 98C41 98 35 94 37 87Z", Ink.CREAM),
        Layer("M38 80Q42 83 46 80M53 80Q57 83 61 80", line = Ink.LINE, width = 1.8f),
        Layer("M47.5 87H51.5L49.5 89Z", Ink.LINE, Ink.LINE, 1.4f),
        Layer("M92 40H102L92 50H102M104 26H112L104 34H112", line = Ink.LINE, width = 2f),
    ),
)

private fun Ink.resolve(colors: StenoColors): Color? = when (this) {
    Ink.NONE -> null
    Ink.LINE -> colors.mascotLine
    Ink.FUR -> colors.mascotFur
    Ink.CREAM -> colors.mascotCream
    Ink.PAPER -> colors.mascotPaper
    Ink.ACCENT -> colors.mascotAccent
}

private fun buildMascot(kind: MascotKind, colors: StenoColors): ImageVector {
    val builder = ImageVector.Builder(
        name = kind.name,
        defaultWidth = 120.dp,
        defaultHeight = 120.dp,
        viewportWidth = 120f,
        viewportHeight = 120f,
    )
    Scenes.getValue(kind).forEach { layer ->
        builder.addPath(
            pathData = PathParser().parsePathString(layer.d).toNodes(),
            fill = layer.fill.resolve(colors)?.let(::SolidColor),
            stroke = layer.line.resolve(colors)?.let(::SolidColor),
            strokeLineWidth = layer.width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}

/** Маскот: кот с блокнотом, вектор на токенах темы. Декоративный, TalkBack его пропускает. */
@Composable
fun Mascot(kind: MascotKind, modifier: Modifier = Modifier, size: Dp = StenoSize.mascot) {
    val colors = StenoTheme.colors
    val vector = remember(kind, colors) { buildMascot(kind, colors) }
    Image(rememberVectorPainter(vector), contentDescription = null, modifier.size(size))
}
