package ru.stenokot.app.pipeline

import java.io.IOException
import java.io.InputStream

/**
 * Сверка файлов моделей с перечнем `config/models.lock`. Файлы читаются блоками и в память целиком
 * не попадают. Отсутствующий или нечитаемый файл считается несовпадением.
 */
class ModelCheck(
    private val ledger: ModelLedger,
    private val open: (String) -> InputStream,
    private val beforeBlock: suspend () -> Unit,
    private val cards: List<ModelCard> = ModelLedger.Cards,
) {
    /** Итог по имени модели. [onRead] получает размер каждого прочитанного блока. */
    suspend fun run(onRead: (Int) -> Unit): Map<String, Boolean> = cards.associate { card ->
        val files = ledger.paths.filter { it.startsWith(card.prefix) }
        var intact = files.isNotEmpty()
        for (path in files) intact = matches(path, onRead) && intact
        card.name to intact
    }

    private suspend fun matches(path: String, onRead: (Int) -> Unit): Boolean = try {
        open(path).use { input ->
            sha256Of(input, beforeBlock, onRead = onRead).equals(ledger.sha256Of(path), ignoreCase = true)
        }
    } catch (failure: IOException) {
        false
    }
}
