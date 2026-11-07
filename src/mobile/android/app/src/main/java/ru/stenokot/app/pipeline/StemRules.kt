package ru.stenokot.app.pipeline

/** Имя файла, его размер и основа выхода, уже закреплённые за другой задачей. */
data class StemClaim(val title: String, val stem: String, val sizeBytes: Long = 0)

/** Имя выходного файла: `<stem>` без расширения и без символов, запрещённых в пути. */
object StemRules {
    private val forbidden = Regex("[/\\\\:*?\"<>|\\p{Cntrl}]")

    /** Возвращает основу имени файла. Пустое имя заменяется нейтральным. */
    fun stem(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringBeforeLast('.')
        val clean = base.replace(forbidden, "_").trim().trimStart('.')
        return clean.ifEmpty { "audio" }
    }

    /**
     * Основа выхода с учётом других задач. Тот же файл получает прежнее имя, поэтому повторная
     * обработка заменяет свой результат. Другой файл с той же основой не затирает чужой результат и
     * получает имя с расширением: `a.mp3` даёт `a.json`, затем `a.wav` даёт `a.wav.json`. Тот же файл
     * узнаётся по имени и размеру, поэтому другая запись с именем `a.mp3` тоже получает своё имя.
     * Неизвестный размер равен нулю и не сравнивается. Регистр не различается: общая память
     * устройства его тоже не различает.
     */
    fun claim(fileName: String, others: List<StemClaim>, sizeBytes: Long = 0): String {
        others.firstOrNull { sameInput(it, fileName, sizeBytes) }?.let { return it.stem }
        val taken = others.map { it.stem.lowercase() }.toSet()
        val plain = stem(fileName)
        if (plain.lowercase() !in taken) return plain
        val extension = extension(fileName)
        val full = if (extension.isEmpty()) plain else "$plain.$extension"
        return sequenceOf(full)
            .plus(generateSequence(2) { it + 1 }.map { "$full ($it)" })
            .first { it.lowercase() !in taken }
    }

    private fun sameInput(other: StemClaim, fileName: String, sizeBytes: Long): Boolean =
        other.title.equals(fileName, ignoreCase = true) &&
            (sizeBytes == 0L || other.sizeBytes == 0L || other.sizeBytes == sizeBytes)

    /** Возвращает расширение в нижнем регистре или пустую строку. */
    fun extension(fileName: String): String {
        val name = fileName.substringAfterLast('/')
        if ('.' !in name) return ""
        val extension = name.substringAfterLast('.').lowercase()
        return extension.takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) }.orEmpty()
    }

    /** Подбирает имя задачи по адресу, пока система не сообщила настоящее. */
    fun titleFromUri(lastSegment: String?): String {
        val tail = lastSegment.orEmpty().substringAfterLast('/').substringAfterLast(':')
        return tail.ifEmpty { "Аудио" }
    }
}
