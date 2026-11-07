package ru.stenokot.app.ui.format

import kotlin.math.abs

/**
 * Выбирает форму слова по правилу русского языка. Склонение считается в коде, а не через
 * ресурсы: системные plurals зависят от языка устройства, а интерфейс только русский.
 */
fun pluralRu(n: Long, one: String, few: String, many: String): String {
    val value = abs(n)
    val lastTwo = value % 100
    val last = value % 10
    return when {
        lastTwo in 11L..14L -> many
        last == 1L -> one
        last in 2L..4L -> few
        else -> many
    }
}

/** Форма слова для счёта после предлога «из»: 1 фрагмента, 45 фрагментов. */
fun genitiveAfterOf(n: Long, one: String, many: String): String {
    val value = abs(n)
    return if (value % 10 == 1L && value % 100 != 11L) one else many
}

/** «3 говорящих». */
fun speakersLabel(n: Int): String =
    "${formatThousands(n.toLong())}$NBSP${pluralRu(n.toLong(), "говорящий", "говорящих", "говорящих")}"

/** «214 реплик». */
fun utterancesLabel(n: Int): String =
    "${formatThousands(n.toLong())}$NBSP${pluralRu(n.toLong(), "реплика", "реплики", "реплик")}"

/** «3 окна». */
fun windowsLabel(n: Int): String =
    "${formatThousands(n.toLong())}$NBSP${pluralRu(n.toLong(), "окно", "окна", "окон")}"

/** «1 файл». */
fun filesLabel(n: Int): String =
    "${formatThousands(n.toLong())}$NBSP${pluralRu(n.toLong(), "файл", "файла", "файлов")}"

/** «6 моделей». */
fun modelsLabel(n: Int): String =
    "${formatThousands(n.toLong())}$NBSP${pluralRu(n.toLong(), "модель", "модели", "моделей")}"

/** «42 минуты». */
fun minutesLabel(n: Long): String = "$n$NBSP${pluralRu(n, "минута", "минуты", "минут")}"

/** «2 часа». */
fun hoursLabel(n: Long): String = "$n$NBSP${pluralRu(n, "час", "часа", "часов")}"

/** «45 секунд». */
fun secondsLabel(n: Long): String = "$n$NBSP${pluralRu(n, "секунда", "секунды", "секунд")}"

/** «42 процента» для озвучивания. */
fun percentSpoken(fraction: Float): String {
    val value = percentValue(fraction).toLong()
    return "$value ${pluralRu(value, "процент", "процента", "процентов")}"
}
