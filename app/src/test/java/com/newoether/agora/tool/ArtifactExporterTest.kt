package com.newoether.agora.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArtifactExporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun sanitizeFileName() {
        assertEquals("Report_1.md", ArtifactExporter.sanitizeFileName("Report 1", "md"))
        assertEquals("abcdefghi.pdf", ArtifactExporter.sanitizeFileName("a/b:c*d?e\"f<g>h|i", "pdf"))
        assertEquals("artifact.md", ArtifactExporter.sanitizeFileName("   ", "md"))
        val long = ArtifactExporter.sanitizeFileName("x".repeat(200), "md")
        assertTrue(long.length <= ArtifactExporter.MAX_FILENAME_LENGTH)
        assertTrue(long.endsWith(".md"))
    }

    @Test
    fun checkContentSize_capsAt1MB() {
        ArtifactExporter.checkContentSize(ByteArray(100))
        try {
            ArtifactExporter.checkContentSize(ByteArray(ArtifactExporter.MAX_MARKDOWN_BYTES + 1))
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun saveMarkdown_writesUtf8() {
        val dir = temp.newFolder("artifacts")
        val file = ArtifactExporter.saveMarkdown(dir, "notes.md", "# T\n\nHéllo → ∞")
        assertEquals("# T\n\nHéllo → ∞", file.readText(Charsets.UTF_8))
    }

    @Test
    fun saveMarkdown_rejectsTraversal() {
        val dir = temp.newFolder("artifacts2")
        try {
            ArtifactExporter.saveMarkdown(dir, "../evil.md", "x")
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun workspaceTreeUri_acceptsContentOnly() {        assertEquals(null, ArtifactExporter.workspaceTreeUri(""))
        assertEquals(null, ArtifactExporter.workspaceTreeUri("/sdcard/Download"))
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3ADownload",
            ArtifactExporter.workspaceTreeUri("content://com.android.externalstorage.documents/tree/primary%3ADownload"),
        )
    }

    @Test
    fun parseMarkdownBlocks_structure() {
        val blocks = ArtifactExporter.parseMarkdownBlocks(
            "Report",
            "# H1\n\n## H2\n\n- bullet\n\n1. first\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n![cap](img.png)\n\nSome **bold** and `code` text.",
        )
        assertTrue(blocks[0] is ArtifactExporter.PdfBlock.Title)
        assertTrue(blocks.any { it is ArtifactExporter.PdfBlock.Heading && it.level == 1 })
        assertTrue(blocks.any { it is ArtifactExporter.PdfBlock.Heading && it.level == 2 })
        assertTrue(blocks.any { it is ArtifactExporter.PdfBlock.Bullet && it.number == null })
        assertTrue(blocks.any { it is ArtifactExporter.PdfBlock.Bullet && it.number == 1 })
        val table = blocks.first { it is ArtifactExporter.PdfBlock.Table } as ArtifactExporter.PdfBlock.Table
        assertEquals(listOf("A", "B"), table.header)
        assertEquals(listOf(listOf("1", "2")), table.rows)
        val image = blocks.first { it is ArtifactExporter.PdfBlock.Image } as ArtifactExporter.PdfBlock.Image
        assertEquals("img.png", image.key)
        val para = blocks.first {
            it is ArtifactExporter.PdfBlock.Para && it.spans.any { s -> s.bold }
        } as ArtifactExporter.PdfBlock.Para
        assertTrue(para.spans.any { it.code })
    }

    @Test
    fun imageKeys_extractsFilenames() {
        assertEquals(
            listOf("a.png", "b.jpg"),
            ArtifactExporter.imageKeys("See ![x](https://h.com/a.png) and ![y](b.jpg)."),
        )
        assertEquals(emptyList<String>(), ArtifactExporter.imageKeys("no images here"))
    }

    @Test
    fun splitTableRow_trimsPipes() {
        assertEquals(listOf("a", "b c"), ArtifactExporter.splitTableRow("| a | b c |"))
    }

    @Test
    fun parseMarkdownBlocks_dedupesTitleHeading() {
        val blocks = ArtifactExporter.parseMarkdownBlocks("Fruits", "# Fruits\n\nBody text.")
        assertTrue(blocks.filterIsInstance<ArtifactExporter.PdfBlock.Heading>().isEmpty())
        assertTrue(blocks.any { it is ArtifactExporter.PdfBlock.Title })
        val kept = ArtifactExporter.parseMarkdownBlocks("Fruits", "# Other\n\nBody.")
        assertEquals(1, kept.filterIsInstance<ArtifactExporter.PdfBlock.Heading>().size)
    }
}
