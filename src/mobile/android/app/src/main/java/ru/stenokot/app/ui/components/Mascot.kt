package ru.stenokot.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import ru.stenokot.app.R
import ru.stenokot.app.ui.theme.EnterMotion
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.app.ui.theme.breathing
import ru.stenokot.app.ui.theme.enterOnce

/**
 * Сюжет маскота. [sideInset] это доля пустого поля слева от фигуры: она нужна, чтобы прижатый
 * к левому краю кот стоял на одной линии с текстом, а не с границей картинки.
 */
enum class MascotKind(@DrawableRes val art: Int, val sideInset: Float) {
    EMPTY(R.drawable.mascot_empty, 0.185f),
    NO_SPEECH(R.drawable.mascot_no_speech, 0.14f),
    WAITING(R.drawable.mascot_waiting, 0.056f),
    DONE(R.drawable.mascot_done, 0.1875f),
    FILE_ERROR(R.drawable.mascot_file_error, 0.1875f),
    STORAGE(R.drawable.mascot_storage, 0.204f),
    MEMORY(R.drawable.mascot_memory, 0.104f),
    INTERRUPTED(R.drawable.mascot_interrupted, 0.183f),
}

/** Маскот: готовая иллюстрация, тёмный вариант приходит из ресурсов. TalkBack его пропускает. */
@Composable
fun Mascot(kind: MascotKind, modifier: Modifier = Modifier, size: Dp = StenoSize.mascot) {
    Image(painterResource(kind.art), contentDescription = null, modifier.size(size))
}

private const val FIGURE_SHARE = 0.85f
private const val PLATE_WIDTH = 1.48f
private const val PLATE_HEIGHT = 1.24f

/** Во сколько раз картинка больше круга, чтобы фигура любой позы была одной ширины, как на макете. */
private val MascotKind.plateArt: Float
    get() = FIGURE_SHARE / (1f - 2f * sideInset)

/**
 * Маскот на круглой подложке, как на макете. Спящий кот целиком лежит внутри круга, сидящий выступает
 * за него сверху и снизу, и область под него выше. Прозрачное поле картинки может выходить за область,
 * сама фигура нет. Движение либо однократное [motion], либо [breathing] на время ожидания.
 */
@Composable
fun MascotPlate(
    kind: MascotKind,
    modifier: Modifier = Modifier,
    plate: Dp = StenoSize.mascotPlate,
    motion: EnterMotion? = null,
    breathing: Boolean = false,
) {
    val art = Modifier.requiredSize(plate * kind.plateArt)
    val height = if (kind == MascotKind.WAITING) plate else plate * PLATE_HEIGHT
    Box(modifier.size(plate * PLATE_WIDTH, height), contentAlignment = Alignment.Center) {
        Box(Modifier.size(plate).background(StenoTheme.colors.primaryContainer, CircleShape))
        when {
            breathing -> Image(painterResource(kind.art), null, art.breathing())
            motion != null -> Image(painterResource(kind.art), null, art.enterOnce(motion))
            else -> Image(painterResource(kind.art), null, art)
        }
    }
}

/** Маскот, у которого фигура, а не картинка, стоит на левом краю колонки. */
@Composable
fun MascotAtStart(kind: MascotKind, modifier: Modifier = Modifier, size: Dp = StenoSize.mascot) {
    Mascot(kind, modifier.offset(x = -size * kind.sideInset), size)
}
