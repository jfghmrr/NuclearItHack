package ru.stenokot.app.pipeline

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream

class ModelCheckTest {
    private val cards = listOf(
        ModelCard("Две части", "роль", "MIT", "asr/", "asr/a.onnx"),
        ModelCard("Одна часть", "роль", "MIT", "vad/", "vad/b.onnx"),
        ModelCard("Без файлов", "роль", "MIT", "none/", "none/c.onnx"),
    )
    private val a = "первый".toByteArray()
    private val b = "второй".toByteArray()
    private val c = "третий".toByteArray()
    private val ledger = ModelLedger(
        listOf(
            "${sha256(a)} asr/a.onnx",
            "${sha256(b)} asr/b.onnx",
            "${sha256(c)} vad/b.onnx",
        ).joinToString("\n"),
    )

    private fun check(files: Map<String, ByteArray>, pauses: MutableList<Unit> = mutableListOf()) = ModelCheck(
        ledger = ledger,
        open = { path: String ->
            val bytes = files[path] ?: throw FileNotFoundException(path)
            ByteArrayInputStream(bytes) as InputStream
        },
        beforeBlock = { pauses += Unit },
        cards = cards,
    )

    @Test
    fun intactFilesPass() = runTest {
        val files = mapOf("asr/a.onnx" to a, "asr/b.onnx" to b, "vad/b.onnx" to c)
        var read = 0
        val verdicts = check(files).run { read += it }
        assertEquals(mapOf("Две части" to true, "Одна часть" to true, "Без файлов" to false), verdicts)
        assertEquals(a.size + b.size + c.size, read)
    }

    @Test
    fun oneChangedFileFailsItsModelOnly() = runTest {
        val files = mapOf("asr/a.onnx" to a, "asr/b.onnx" to c, "vad/b.onnx" to c)
        val verdicts = check(files).run { }
        assertEquals(false, verdicts["Две части"])
        assertEquals(true, verdicts["Одна часть"])
    }

    @Test
    fun missingFileIsAFailureNotACrash() = runTest {
        val verdicts = check(mapOf("asr/a.onnx" to a, "vad/b.onnx" to c)).run { }
        assertEquals(false, verdicts["Две части"])
        assertEquals(true, verdicts["Одна часть"])
    }

    @Test
    fun everyBlockGivesWayToHeavyStage() = runTest {
        val pauses = mutableListOf<Unit>()
        check(mapOf("asr/a.onnx" to a, "asr/b.onnx" to b, "vad/b.onnx" to c), pauses).run { }
        assertEquals(6, pauses.size)
    }
}
