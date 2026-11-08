package ru.stenokot.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import ru.stenokot.app.ui.theme.StenoMotion
import ru.stenokot.app.ui.theme.StenoSize
import ru.stenokot.app.ui.theme.StenoTheme
import ru.stenokot.app.ui.theme.enterOnce

/**
 * Поза скворца. Все позы нарисованы в одном масштабе на одинаковом холсте, поэтому подложка и
 * размер у них общие. Мало памяти и мало места показывает одна и та же поза.
 */
enum class MascotKind(@DrawableRes val art: Int) {
    EMPTY(R.drawable.mascot_empty),
    NO_SPEECH(R.drawable.mascot_no_speech),
    WAITING(R.drawable.mascot_waiting),
    DONE(R.drawable.mascot_done),
    FILE_ERROR(R.drawable.mascot_file_error),
    STORAGE(R.drawable.mascot_storage),
    MEMORY(R.drawable.mascot_storage),
    INTERRUPTED(R.drawable.mascot_interrupted),
    HELLO(R.drawable.mascot_hello),
    IMPORT(R.drawable.mascot_import),
    RECORD(R.drawable.mascot_record),
    SPEAKERS(R.drawable.mascot_speakers),
    SEARCH(R.drawable.mascot_search),
    EXPORT(R.drawable.mascot_export),
    HELP(R.drawable.mascot_help),
    SLEEP(R.drawable.mascot_sleep),
}

/**
 * Картинка позы. Новая поза сменяет прежнюю плавным наплывом, а не скачком; движение одноразовое
 * и в покое ничего не перерисовывается. При выключенных анимациях система делает смену мгновенной.
 */
@Composable
private fun MascotArt(kind: MascotKind, modifier: Modifier) {
    Crossfade(targetState = kind, animationSpec = tween(StenoMotion.MEDIUM), label = "mascot") { pose ->
        Image(painterResource(pose.art), contentDescription = null, modifier = modifier)
    }
}

/** Маскот без подложки. Появляется один раз за жизнь экрана. TalkBack его пропускает. */
@Composable
fun Mascot(kind: MascotKind, modifier: Modifier = Modifier, size: Dp = StenoSize.mascot) {
    MascotArt(kind, modifier.size(size).enterOnce(EnterMotion.EMPTY))
}

private const val ART_SHARE = 1.22f
private const val PLATE_WIDTH = 1.48f
private const val PLATE_HEIGHT = 1.24f

/**
 * Маскот на круглой подложке, как на макете. Скворец выступает за круг сверху и снизу, поэтому
 * область под него выше круга. Прозрачное поле картинки может выходить за область, сама фигура нет.
 * Появление однократное: [motion] выбирает его характер, без него скворец просто проявляется.
 */
@Composable
fun MascotPlate(
    kind: MascotKind,
    modifier: Modifier = Modifier,
    plate: Dp = StenoSize.mascotPlate,
    motion: EnterMotion? = null,
) {
    Box(
        modifier.size(plate * PLATE_WIDTH, plate * PLATE_HEIGHT).enterOnce(motion ?: EnterMotion.EMPTY),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(plate).background(StenoTheme.colors.mascotPlate, CircleShape))
        MascotArt(kind, Modifier.requiredSize(plate * ART_SHARE))
    }
}
