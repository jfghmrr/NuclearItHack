package ru.stenokot.app.ui.licenses

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.stenokot.app.pipeline.ModelLedger

class LicenseCatalogTest {
    private val entries = LicenseCatalog.groups.flatMap { it.entries }

    @Test
    fun everyEntryHasItsTextInAssets() {
        entries.forEach {
            val file = File("src/main/assets/licenses/${it.file}")
            assertTrue("нет текста для ${it.name}", file.isFile && file.length() > 0)
        }
    }

    @Test
    fun everyLicenseFileIsUsed() {
        val used = entries.map { it.file }.toSet()
        File("src/main/assets/licenses").list().orEmpty().forEach { assertTrue("лишний файл $it", it in used) }
    }

    @Test
    fun everyModelLeadsToItsLicense() {
        ModelLedger.Cards.forEach { assertNotNull("нет лицензии для ${it.name}", LicenseCatalog.forModel(it.name)) }
        assertEquals("pyannote", LicenseCatalog.forModel("pyannote-segmentation-3-0 int8")?.id)
        assertEquals("titanet", LicenseCatalog.forModel("TitaNet-small fp32")?.id)
        assertNull(LicenseCatalog.forModel("sherpa-onnx"))
    }

    @Test
    fun idsAreUnique() {
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        entries.forEach { assertEquals(it, LicenseCatalog.entry(it.id)) }
    }

    @Test
    fun paragraphsAreReflowedAndRulesDropped() {
        val text = "-----\nTITLE LINE\n-----\n\nPREAMBLE\nfirst line\nsecond line\n\n  third\n"
        assertEquals(listOf("TITLE LINE", "PREAMBLE", "first line second line", "third"), licenseParagraphs(text))
    }

    @Test
    fun headingsAreShortCapitalLines() {
        assertTrue(isLicenseHeading("PREAMBLE"))
        assertFalse(isLicenseHeading("Preamble"))
        assertFalse(isLicenseHeading("1."))
    }
}
