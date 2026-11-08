package ru.stenokot.app.ui.result

import org.junit.Assert.assertEquals
import org.junit.Test

/** Системный файловый менеджер понимает только путь внутри общего хранилища. */
class ExportPanelTest {
    private val root = "/storage/emulated/0"

    @Test
    fun fullPathBecomesRelativeFolder() {
        assertEquals("Download/Skvor", outputFolder("$root/Download/Skvor/clip.json", root))
    }

    @Test
    fun relativePathStaysAsItIs() {
        assertEquals("Download/Skvor", outputFolder("Download/Skvor/clip.json", root))
    }

    @Test
    fun fileInTheRootHasNoFolder() {
        assertEquals("", outputFolder("$root/clip.json", root))
    }
}
