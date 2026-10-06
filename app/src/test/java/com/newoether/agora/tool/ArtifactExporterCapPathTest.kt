package com.newoether.agora.tool

import android.app.Application
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Regression test for the pdf_render NullPointerException seen on-device
 * ("Attempt to invoke virtual method 'void android.graphics.Canvas.drawText..'
 * on a null object reference").
 *
 * Root cause: PdfDocument.Page.getCanvas() returns null after finishPage()
 * (AOSP). When the 100-page cap was hit mid-document, newPage() finished the
 * page but created no new one, and the render loop kept drawing on the
 * finished page. Every draw site must stop once the cap is hit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtifactExporterCapPathTest {

    @Test
    fun veryLongDoc_completesTruncatedWithoutNpe() {
        // ~6000 bullets -> forces the 100-page cap.
        val md = buildString {
            repeat(6000) { i -> appendLine("- bullet number $i with some extra words to fill the line") }
        }
        val tmp = File.createTempFile("cap_bullets", ".pdf")
        try {
            val result = ArtifactExporter.savePdf(tmp, "Cap repro", md)
            assertTrue("expected truncation at page cap", result.truncated)
            assertTrue("expected 100 pages", result.pages == 100)
            assertTrue("expected a non-empty PDF", tmp.length() > 0)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun longTableDoc_completesTruncatedWithoutNpe() {
        // Tall table that forces repeated page breaks via the table path
        // (drawRow/drawHeaderRow call onNewPage without a capped check).
        val md = buildString {
            appendLine("| Name | Description |")
            appendLine("|---|---|")
            repeat(3000) { i ->
                appendLine("| item $i | " + "description text ".repeat(20) + "|")
            }
        }
        val tmp = File.createTempFile("cap_table", ".pdf")
        try {
            val result = ArtifactExporter.savePdf(tmp, "Cap table repro", md)
            assertTrue("expected truncation at page cap", result.truncated)
            assertTrue("expected a non-empty PDF", tmp.length() > 0)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun mediumDoc_rendersFully() {
        val md = buildString {
            appendLine("# Report")
            repeat(800) { i -> appendLine("- bullet number $i") }
        }
        val tmp = File.createTempFile("cap_medium", ".pdf")
        try {
            val result = ArtifactExporter.savePdf(tmp, "Medium", md)
            assertTrue("medium doc should not hit the cap", !result.truncated)
            assertTrue("expected a non-empty PDF", tmp.length() > 0)
        } finally {
            tmp.delete()
        }
    }
}
