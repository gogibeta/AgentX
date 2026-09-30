package com.newoether.agora.tool

import android.graphics.Paint
import android.graphics.Typeface
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Text layout needs Android graphics — Robolectric, mirroring LatexMathAxisTest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtifactExporterPdfTest {

    private val body = Paint().apply {
        typeface = Typeface.DEFAULT; textSize = 11.5f; isAntiAlias = true
    }
    private val bold = Paint().apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 11.5f
        isAntiAlias = true
    }

    @Test
    fun layoutSpans_wrapsAndKeepsPaints() {
        val paints = ArtifactExporter.Paints()
        val spans = listOf(
            ArtifactExporter.TextSpan("Hello world this is a long line that must wrap "),
            ArtifactExporter.TextSpan("bold part here", bold = true),
        )
        val lines = ArtifactExporter.layoutSpans(spans, paints, 200)
        assertTrue(lines.size >= 2)
        // Bold words keep the bold paint after wrapping.
        val all = lines.flatten()
        val boldWords = all.filter { it.second === paints.bodyBold }.map { it.first }
        assertTrue(boldWords.contains("bold"))
        // Plain words keep the body paint.
        assertTrue(all.any { it.first == "Hello" && it.second === paints.body })
    }

    @Test
    fun layoutSpans_emptyIsOneBlankLine() {
        val paints = ArtifactExporter.Paints()
        val lines = ArtifactExporter.layoutSpans(
            listOf(ArtifactExporter.TextSpan("")), paints, 200,
        )
        assertTrue(lines.size == 1)
    }
}
