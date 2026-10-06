package com.newoether.agora.tool

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.OutputStream

/**
 * Regression test for the pdf_render NullPointerException seen on-device
 * ("Attempt to invoke virtual method 'void android.graphics.Canvas.drawText..'
 * on a null object reference").
 *
 * Root cause: PdfDocument.Page.getCanvas() returns null after finishPage()
 * (AOSP). When the 100-page cap was hit mid-document, newPage() finished the
 * page but created no new one, and the render loop kept drawing on the
 * finished page. Every draw site must stop once the cap is hit.
 *
 * The test injects a fake document via [ArtifactExporter.savePdf]'s
 * documentFactory seam: a real PdfDocument cannot be created under Robolectric
 * (its nativeCreateDocument() is unimplemented, so the very first startPage
 * throws "document is closed!" — AOSP's throwIfClosed checks the native
 * handle). The fake reproduces the AOSP page-lifecycle contract instead — a
 * finished page's canvas throws NPE, exactly like the on-device null canvas —
 * so any draw past the cap fails the test. NATIVE graphics mode is kept so
 * text measurement (which drives pagination) is real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtifactExporterCapPathTest {

    /** Fake document reproducing the AOSP page lifecycle (see class KDoc). */
    private class FakePdfDoc : ArtifactExporter.PdfDoc {
        var startedPages = 0
            private set
        var closed = false
            private set

        override fun startPage(pageNum: Int): ArtifactExporter.PdfPage {
            check(!closed) { "document is closed!" }
            startedPages++
            return FakePdfPage()
        }

        override fun finishPage(page: ArtifactExporter.PdfPage) {
            (page as FakePdfPage).finished = true
        }

        override fun writeTo(out: OutputStream) {
            out.write("%PDF-1.4\n%fake\n".toByteArray())
        }

        override fun close() {
            closed = true
        }
    }

    private class FakePdfPage : ArtifactExporter.PdfPage {
        var finished = false
        private val bitmap by lazy { Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888) }
        private val backingCanvas by lazy { Canvas(bitmap) }
        override val canvas: Canvas
            get() = if (finished) {
                // AOSP: getCanvas() is null after finishPage(); drawing on it
                // NPE'd on-device. Fail loudly here for the same violation.
                throw NullPointerException("canvas used after finishPage (page cap hit)")
            } else {
                backingCanvas
            }
    }

    private fun render(title: String, markdown: String): Pair<FakePdfDoc, ArtifactExporter.PdfResult> {
        val doc = FakePdfDoc()
        val tmp = File.createTempFile("cap", ".pdf")
        try {
            val result = ArtifactExporter.savePdf(tmp, title, markdown, documentFactory = { doc })
            assertTrue("expected a non-empty PDF", tmp.length() > 0)
            return doc to result
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun veryLongDoc_completesTruncatedWithoutNpe() {
        // ~6000 bullets -> forces the 100-page cap.
        val md = buildString {
            repeat(6000) { i -> appendLine("- bullet number $i with some extra words to fill the line") }
        }
        val (doc, result) = render("Cap repro", md)
        assertTrue("expected truncation at page cap", result.truncated)
        assertTrue("expected 100 pages, got ${result.pages}", result.pages == 100)
        assertTrue("expected the document to be closed", doc.closed)
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
        val (_, result) = render("Cap table repro", md)
        assertTrue("expected truncation at page cap", result.truncated)
    }

    @Test
    fun mediumDoc_rendersFully() {
        val md = buildString {
            appendLine("# Report")
            repeat(800) { i -> appendLine("- bullet number $i") }
        }
        val (_, result) = render("Medium", md)
        assertTrue("medium doc should not hit the cap", !result.truncated)
    }
}
