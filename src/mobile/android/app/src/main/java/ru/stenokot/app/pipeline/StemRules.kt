package ru.stenokot.app.pipeline

/** Имя выходного файла: `<stem>` без расширения и без символов, запрещённых в пути. */
object StemRules {
    private val forbidden = Regex("[/\\\\:*?\"<>|\\p{Cntrl}]")

    /** Возвращает основу имени файла. Пустое имя заменяется нейтральным. */
    fun stem(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringBeforeLast('.')
        val clean = base.replace(forbidden, "_").trim().trimStart('.')
        return clean.ifEmpty { "audio" }
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
