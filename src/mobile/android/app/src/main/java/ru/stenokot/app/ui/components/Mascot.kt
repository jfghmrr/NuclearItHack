package ru.stenokot.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import ru.stenokot.app.R
import ru.stenokot.app.ui.theme.StenoSize

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

/** Маскот, у которого фигура, а не картинка, стоит на левом краю колонки. */
@Composable
fun MascotAtStart(kind: MascotKind, modifier: Modifier = Modifier, size: Dp = StenoSize.mascot) {
    Mascot(kind, modifier.offset(x = -size * kind.sideInset), size)
}
