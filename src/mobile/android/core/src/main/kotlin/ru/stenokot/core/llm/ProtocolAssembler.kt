package ru.stenokot.core.llm

import ru.stenokot.core.contract.speakerId
import ru.stenokot.core.protocol.ProtocolDocument
import ru.stenokot.core.protocol.ProtocolItem
import ru.stenokot.core.protocol.ProtocolSection

/** Обработанное окно вместе с разобранным ответом. */
data class WindowResult(val window: LlmWindow, val extract: WindowExtract)

/** Собирает protocol.json кодом: текст, говорящий и время копируются из реплики без изменений. */
object ProtocolAssembler {
    /** Строит документ по обработанным окнам в любом порядке. Окна с неясным алфавитом пропускаются. */
    fun assemble(results: List<WindowResult>, totalMs: Long): ProtocolDocument {
        val used = results
            .filter { it.extract.outcome == WindowOutcome.OK }
            .sortedBy { it.window.startMs }
        val seen = HashSet<Pair<ProtocolKind, Int>>()
        val sections = used.map { section(it, seen) }
        return ProtocolDocument(sections, coveredMs(used), totalMs)
    }

    private fun section(result: WindowResult, seen: MutableSet<Pair<ProtocolKind, Int>>): ProtocolSection {
        val window = result.window
        val items = result.extract.items
            .filter { seen.add(it.kind to it.utterance.id) }
            .sortedBy { it.utterance.startMs }
            .map { item ->
                val utterance = item.utterance
                ProtocolItem(
                    kind = item.kind.value,
                    utterance = utterance.id,
                    speakerId = speakerId(utterance.speaker),
                    startMs = utterance.startMs,
                    text = utterance.text,
                )
            }
        return ProtocolSection(
            title = result.extract.topic ?: interval(window.startMs, window.endMs),
            startMs = window.startMs,
            endMs = window.endMs,
            firstUtterance = window.utterances.first().id,
            items = items,
        )
    }

    private fun coveredMs(results: List<WindowResult>): Long {
        var total = 0L
        var coveredTo = Long.MIN_VALUE
        for (result in results) {
            val from = maxOf(result.window.startMs, coveredTo)
            val to = result.window.endMs
            if (to > from) {
                total += to - from
                coveredTo = to
            }
        }
        return total
    }

    /** Название раздела, тему которого проверка не приняла. */
    private fun interval(startMs: Long, endMs: Long): String = "${clock(startMs)} - ${clock(endMs)}"

    private fun clock(ms: Long): String {
        val seconds = ms / 1000
        val hours = seconds / 3600
        val minutes = (seconds / 60) % 60
        val rest = seconds % 60
        val tail = "${minutes.toString().padStart(2, '0')}:${rest.toString().padStart(2, '0')}"
        return if (hours > 0) "$hours:$tail" else tail
    }
}
