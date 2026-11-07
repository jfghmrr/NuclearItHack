package ru.stenokot.app.ui.verification

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.stenokot.app.domain.ModelCheckUi
import ru.stenokot.app.pipeline.ModelLedger

class ModelFactsTest {
    @Test
    fun formatIsTheLastWordOfTheName() {
        assertEquals(ModelTitle("GigaAM v3 e2e RNNT", "int8"), modelTitle("GigaAM v3 e2e RNNT int8"))
        assertEquals(ModelTitle("TitaNet-small", "fp32"), modelTitle("TitaNet-small fp32"))
        assertEquals(ModelTitle("Qwen3-0.6B", "Q4_K_M"), modelTitle("Qwen3-0.6B Q4_K_M"))
    }

    @Test
    fun nameWithoutFormatStaysWhole() {
        assertEquals(ModelTitle("Silero VAD", null), modelTitle("Silero VAD"))
        assertEquals(ModelTitle("одно", null), modelTitle("одно"))
    }

    @Test
    fun everyRealModelNameParses() {
        val formats = ModelLedger.Cards.map { modelTitle(it.name).format }
        assertEquals(listOf("int8", "int8", null, "int8", "fp32", "Q4_K_M"), formats)
    }

    @Test
    fun noCheckMeansNoVerdict() {
        assertEquals(ModelIntegrity.NOT_CHECKED, modelIntegrity("м", ModelCheckUi()))
        assertEquals(ModelIntegrity.CHECKING, modelIntegrity("м", ModelCheckUi(running = true, verdicts = mapOf("м" to true))))
        assertEquals(ModelIntegrity.PASSED, modelIntegrity("м", ModelCheckUi(verdicts = mapOf("м" to true))))
        assertEquals(ModelIntegrity.FAILED, modelIntegrity("м", ModelCheckUi(verdicts = mapOf("м" to false))))
        assertEquals(ModelIntegrity.NOT_CHECKED, modelIntegrity("другая", ModelCheckUi(verdicts = mapOf("м" to true))))
    }
}
