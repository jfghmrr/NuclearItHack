package ru.stenokot.app.ui

import android.net.Uri
import androidx.compose.runtime.Stable
import ru.stenokot.app.ui.nav.Navigator

/** Общие действия экранов: навигация и системный выбор файла. */
@Stable
class AppActions(
    val navigator: Navigator,
    val openPicker: (allFiles: Boolean) -> Unit,
    val importFiles: (List<Uri>) -> Unit,
) {
    /** Шаг назад по стеку экранов. */
    val back: () -> Unit = { navigator.back() }

    /** Выбор файла с фильтром аудио. */
    val importAudio: () -> Unit = { openPicker(false) }

    /** Выбор файла без фильтра. */
    val importAny: () -> Unit = { openPicker(true) }
}
