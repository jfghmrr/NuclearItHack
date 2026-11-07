package ru.stenokot.app.pipeline

/** Имя файла и основа выхода, уже закреплённые за другой задачей. */
data class StemClaim(val title: String, val stem: String)

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
     * обработка заменяет свой результат. Файл с другим расширением и той же основой не затирает
     * чужой результат и получает имя с расширением: `a.mp3` даёт `a.json`, затем `a.wav` даёт
     * `a.wav.json`. Регистр не различается: общая память устройства его тоже не различает.
     */
    fun claim(fileName: String, others: List<StemClaim>): String {
        others.firstOrNull { it.title.equals(fileName, ignoreCase = true) }?.let { return it.stem }
        val taken = others.map { it.stem.lowercase() }.toSet()
        val plain = stem(fileName)
        if (plain.lowercase() !in taken) return plain
        val extension = extension(fileName)
        val full = if (extension.isEmpty()) plain else "$plain.$extension"
        return sequenceOf(full)
            .plus(generateSequence(2) { it + 1 }.map { "$full ($it)" })
            .first { it.lowercase() !in taken }
    }

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
