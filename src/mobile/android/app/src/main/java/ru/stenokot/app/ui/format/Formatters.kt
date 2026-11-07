package ru.stenokot.app.ui.format

import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong
import ru.stenokot.core.model.PreflightInfo

/** Неразрывный пробел: число не отрывается от единицы измерения. */
const val NBSP = ' '

private const val MEBIBYTE = 1_048_576.0
private const val KIBIBYTE = 1_024.0
private const val GIGABYTE = 1_000_000_000.0
private const val MEGABYTE = 1_000_000.0
private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L

private val MONTHS = arrayOf(
    "янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек",
)

private fun twoDigits(value: Long): String = if (value < 10) "0$value" else value.toString()

/** Таймкод: `мм:сс`, а от часа `ч:мм:сс`. */
fun formatTimecode(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    return if (hours > 0) {
        "$hours:${twoDigits(minutes)}:${twoDigits(seconds)}"
    } else {
        "${twoDigits(minutes)}:${twoDigits(seconds)}"
    }
}

/** Диапазон таймкодов через дефис с пробелами. */
fun formatTimeRange(startMs: Long, endMs: Long): String =
    "${formatTimecode(startMs)} - ${formatTimecode(endMs)}"

/** Целый процент без округления вверх: незаконченная стадия не покажет 100. */
fun percentValue(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100f + 0.0001f).toInt()

/** Таймкод для TalkBack после предлогов «с» и «до»: «2 минут 4 секунд». */
fun spokenTimecode(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val hours = total / 3600
    val minutes = total % 3600 / 60
    val seconds = total % 60
    val time = "$minutes ${genitiveAfterOf(minutes, "минуты", "минут")} " +
        "$seconds ${genitiveAfterOf(seconds, "секунды", "секунд")}"
    return if (hours > 0) "$hours ${genitiveAfterOf(hours, "часа", "часов")} $time" else time
}

/** Процент с неразрывным пробелом перед знаком. */
fun formatPercent(fraction: Float): String = "${percentValue(fraction)}$NBSP%"

/** Целое с разделением тысяч неразрывным пробелом. */
fun formatThousands(value: Long): String {
    val digits = kotlin.math.abs(value).toString()
    val grouped = buildString(digits.length + digits.length / 3) {
        digits.forEachIndexed { index, digit ->
            if (index > 0 && (digits.length - index) % 3 == 0) append(NBSP)
            append(digit)
        }
    }
    return if (value < 0) "-$grouped" else grouped
}

/** Число с одним знаком после запятой, нулевая дробь отбрасывается. */
fun formatDecimal(value: Double): String {
    val tenths = (value * 10).roundToLong()
    val whole = tenths / 10
    val fraction = tenths % 10
    return if (fraction == 0L) whole.toString() else "$whole,$fraction"
}

/** Размер файла или модели в МиБ. */
fun formatMib(bytes: Long): String = "${formatDecimal(bytes / MEBIBYTE)}$NBSP" + "МиБ"

/** Размер небольшого файла: от мебибайта в МиБ, ниже в КиБ, чтобы JSON не выглядел пустым. */
fun formatFileSize(bytes: Long): String =
    if (bytes >= MEBIBYTE) {
        formatMib(bytes)
    } else {
        "${(bytes / KIBIBYTE).roundToLong().coerceAtLeast(1)}$NBSP" + "КиБ"
    }

/** Значение в МиБ без единицы: для строк «214 из 378 МиБ». */
fun formatMibValue(bytes: Long): String = formatDecimal(bytes / MEBIBYTE)

/** Свободное место: от гигабайта в ГБ, ниже в МБ. */
fun formatFreeSpace(bytes: Long): String =
    if (bytes >= GIGABYTE) {
        "${formatDecimal(bytes / GIGABYTE)}$NBSP" + "ГБ"
    } else {
        "${(bytes / MEGABYTE).roundToLong()}$NBSP" + "МБ"
    }

/** Дата в истории: день, месяц из трёх букв, время. */
fun formatHistoryDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = Instant.ofEpochMilli(epochMs).atZone(zone)
    val month = MONTHS[time.monthValue - 1]
    return "${time.dayOfMonth} $month, ${twoDigits(time.hour.toLong())}:${twoDigits(time.minute.toLong())}"
}

/** Длительность словами: «45 секунд», «42 минуты», «2 часа 49 минут». */
fun formatDurationWords(ms: Long): String {
    val value = ms.coerceAtLeast(0)
    if (value < MINUTE_MS) return secondsLabel((value / 1000).coerceAtLeast(1))
    val minutes = (value + MINUTE_MS / 2) / MINUTE_MS
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0L -> minutesLabel(rest)
        rest == 0L -> hoursLabel(hours)
        else -> "${hoursLabel(hours)} ${minutesLabel(rest)}"
    }
}

/** Диапазон времени словами: нижняя граница вниз, верхняя вверх, чтобы оценка не льстила. */
fun formatEstimateRange(minMs: Long, maxMs: Long): String {
    val low = minOf(minMs, maxMs).coerceAtLeast(0)
    val high = maxOf(minMs, maxMs).coerceAtLeast(0)
    if (high < MINUTE_MS) return "меньше минуты"
    if (high >= 3 * HOUR_MS) {
        val from = floor(low.toDouble() / HOUR_MS).toLong().coerceAtLeast(1)
        val to = ceil(high.toDouble() / HOUR_MS).toLong()
        return if (from == to) hoursLabel(to) else "$from–${hoursLabel(to)}"
    }
    val from = floor(low.toDouble() / MINUTE_MS).toLong().coerceAtLeast(1)
    val to = ceil(high.toDouble() / MINUTE_MS).toLong()
    return if (from == to) minutesLabel(to) else "$from–${minutesLabel(to)}"
}

/**
 * Строка предварительной проверки: длительность, оценка времени и свободное место.
 * Оценки без данных нет, а оценка без замеров на этом устройстве подписана как примерная.
 */
fun formatPreflight(info: PreflightInfo): String {
    val parts = mutableListOf<String>()
    info.durationMs?.let { parts += "Запись ${formatDurationWords(it)}." }
    val min = info.estimateMinMs
    val max = info.estimateMaxMs
    if (min != null && max != null) {
        val range = formatEstimateRange(min, max)
        parts += if (info.estimateMeasured) {
            "Ожидаемое время обработки $range."
        } else {
            "Примерная оценка времени обработки: $range."
        }
    }
    parts += "Свободно ${formatFreeSpace(info.freeBytes)}"
    return parts.joinToString(" ")
}

/** Оценка оставшегося времени или null, если подтвердить её нечем. */
fun formatRemaining(minMs: Long?, maxMs: Long?): String? {
    if (minMs == null || maxMs == null) return null
    return "Осталось примерно ${formatEstimateRange(minMs, maxMs)}"
}
