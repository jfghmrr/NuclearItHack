package ru.stenokot.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

/** Стек экранов на состоянии Compose. Отдельная библиотека навигации здесь не нужна. */
@Stable
class Navigator(initial: List<Route>) {
    private val stack = mutableStateListOf<Route>().apply { addAll(initial) }

    /** Верхний экран. */
    val current: Route get() = stack.last()

    /** Есть куда возвращаться. */
    val canGoBack: Boolean get() = stack.size > 1

    /** Открывает [route] поверх текущего экрана. Повтор того же экрана ничего не меняет. */
    fun push(route: Route) {
        if (stack.last() != route) stack.add(route)
    }

    /** Заменяет верхний экран, путь назад остаётся прежним. */
    fun replaceTop(route: Route) {
        if (stack.last() == route) return
        if (stack.size == 1) stack.add(route) else stack[stack.lastIndex] = route
    }

    /** Открывает [route] сразу над Главной. */
    fun resetTo(route: Route) {
        stack.clear()
        stack.add(Route.Home)
        if (route != Route.Home) stack.add(route)
    }

    /** Шаг назад. Значение false означает, что возвращаться некуда. */
    fun back(): Boolean {
        if (!canGoBack) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Текущий стек для сохранения. */
    internal fun snapshot(): List<String> = stack.map(Route::encode)
}

private val NavigatorSaver = Saver<Navigator, ArrayList<String>>(
    save = { ArrayList(it.snapshot()) },
    restore = { saved -> Navigator(saved.map(::decodeRoute)) },
)

/** Стек, который переживает поворот и пересоздание активности. */
@Composable
fun rememberNavigator(): Navigator = rememberSaveable(saver = NavigatorSaver) {
    Navigator(listOf(Route.Home))
}
