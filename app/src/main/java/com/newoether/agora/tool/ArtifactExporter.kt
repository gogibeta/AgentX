package com.newoether.agora.tool

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream

/**
 * Agent artifact output: Markdown files and rendered PDF reports.
 *
 * Destination priority: the user's picked Agent workspace folder (SAF tree URI from
 * `Settings -> Agent -> Workspace folder`, persisted with
 * `takePersistableUriPermission`) → app `cacheDir/artifacts` fallback. Filenames are
 * sanitized, content is capped, PDF pages are capped — an agent must never fill
 * storage or escape the workspace (relative-name only, no `..`, no `/`).
 *
 * PDF reports use a real document model (title, heading levels, bold/code spans,
 * bullets, numbered lists, tables with repeating headers + zebra, dividers,
 * embedded images, footers) instead of a monospace dump.
 */
object ArtifactExporter {
    const val MAX_MARKDOWN_BYTES = 1_000_000
    const val MAX_PDF_PAGES = 100
    const val MAX_FILENAME_LENGTH = 64
    const val MAX_IMAGE_BYTES = 5_000_000
    /** Cap for pre-built files registered via save_artifact(source_path=...). */
    const val MAX_SOURCE_BYTES = 50_000_000

    data class SavedArtifact(
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        /** "workspace" when written to the user's folder, else "app storage". */
        val destination: String,
    )

    fun sanitizeFileName(title: String, extension: String): String {
        var base = title.trim().replace(Regex("""[\\/:*?"<>|]"""), "")
            .replace(Regex("""\s+"""), "_")
        if (base.isEmpty()) base = "artifact"
        base = base.take(MAX_FILENAME_LENGTH - extension.length - 1)
        return "$base.$extension"
    }

    fun checkContentSize(content: ByteArray) {
        require(content.size <= MAX_MARKDOWN_BYTES) {
            "Artifact content too large (${content.size} bytes, max $MAX_MARKDOWN_BYTES)"
        }
    }

    /** Write a Markdown file into a plain directory. Returns the file. */
    fun saveMarkdown(dir: File, fileName: String, markdown: String): File {
        require(!fileName.contains("..") && !fileName.contains("/")) { "Unsafe file name" }
        val bytes = markdown.toByteArray(Charsets.UTF_8)
        checkContentSize(bytes)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    // ── Markdown document model (pure — unit-tested on JVM) ──────────

    data class TextSpan(val text: String, val bold: Boolean = false, val code: Boolean = false)

    sealed interface PdfBlock {
        data class Title(val text: String) : PdfBlock
        data class Heading(val level: Int, val text: String) : PdfBlock
        data class Para(val spans: List<TextSpan>) : PdfBlock
        data class Bullet(val spans: List<TextSpan>, val number: Int? = null) : PdfBlock
        data class Table(val header: List<String>, val rows: List<List<String>>) : PdfBlock
        data class Image(val key: String, val alt: String) : PdfBlock
        data object Divider : PdfBlock
        data object Gap : PdfBlock
    }

    private val IMAGE_REF = Regex("""!\[([^\]]*)\]\(([^)\s]+)[^)]*\)""")
    private val TABLE_SEP = Regex("""^\s*\|?(\s*:?-+:?\s*\|)+\s*$""")

    /** Image lookup keys (`![alt](src)` → filename) referenced by a document. */
    internal fun imageKeys(markdown: String): List<String> =
        IMAGE_REF.findAll(markdown)
            .map { it.groupValues[2].substringAfterLast("/").substringBefore("?") }
            .filter { it.isNotBlank() && !it.contains("..") }
            .distinct().toList()

    internal fun parseSpans(text: String): List<TextSpan> {
        val spans = ArrayList<TextSpan>()
        var i = 0
        val current = StringBuilder()
        var bold = false
        var code = false
        fun flush() {
            if (current.isNotEmpty()) {
                spans.add(TextSpan(current.toString(), bold, code))
                current.clear()
            }
        }
        while (i < text.length) {
            when {
                !code && text.startsWith("**", i) -> {
                    val close = text.indexOf("**", i + 2)
                    if (close > i + 2) {
                        flush()
                        spans.add(TextSpan(text.substring(i + 2, close), true, false))
                        i = close + 2
                    } else {
                        current.append(text[i]); i++
                    }
                }
                text[i] == '`' -> {
                    // Inline code: single backticks only (fences are stripped earlier).
                    val close = text.indexOf('`', i + 1)
                    if (close > i + 1) {
                        flush()
                        spans.add(TextSpan(text.substring(i + 1, close), false, true))
                        i = close + 1
                    } else {
                        current.append(text[i]); i++
                    }
                }
                else -> {
                    current.append(text[i]); i++
                }
            }
        }
        flush()
        return spans.ifEmpty { listOf(TextSpan("")) }
    }

    internal fun parseMarkdownBlocks(title: String, markdown: String): List<PdfBlock> {
        val blocks = ArrayList<PdfBlock>()
        blocks.add(PdfBlock.Title(title))        // Fenced code blocks render as plain code paragraphs.
        val text = markdown.replace(Regex("```[a-zA-Z]*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)) {
            "\n" + it.groupValues[1].trimIndent() + "\n"
        }
        val lines = text.split('\n')
        var i = 0
        var titleConsumed = false
        while (i < lines.size) {
            val line = lines[i]
            val trim = line.trim()
            // Skip a leading H1 that merely repeats the report title.
            if (!titleConsumed && trim.startsWith("# ")) {
                titleConsumed = true
                if (trim.removePrefix("# ").trim().equals(title.trim(), ignoreCase = true)) {
                    i++
                    continue
                }
            }
            when {
                trim.isEmpty() -> {
                    if (blocks.lastOrNull() !is PdfBlock.Gap) blocks.add(PdfBlock.Gap)
                    i++
                }
                trim.startsWith("####") || trim.startsWith("###") ->
                    blocks.add(PdfBlock.Heading(3, trim.trimStart('#').trim())).also { i++ }
                trim.startsWith("## ") ->
                    blocks.add(PdfBlock.Heading(2, trim.removePrefix("## ").trim())).also { i++ }
                trim.startsWith("# ") ->
                    blocks.add(PdfBlock.Heading(1, trim.removePrefix("# ").trim())).also { i++ }
                trim == "---" || trim == "***" || trim == "___" -> {
                    blocks.add(PdfBlock.Divider); i++
                }
                trim.startsWith("|") && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) -> {
                    val header = splitTableRow(trim)
                    val rows = ArrayList<List<String>>()
                    i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        rows.add(splitTableRow(lines[i].trim()))
                        i++
                    }
                    if (rows.isNotEmpty()) blocks.add(PdfBlock.Table(header, rows))
                }
                trim.startsWith("- ") || trim.startsWith("* ") -> {
                    blocks.add(PdfBlock.Bullet(parseSpans(trim.substring(2).trim()))); i++
                }
                Regex("""^\d+[.)]\s""").containsMatchIn(trim) -> {
                    val number = trim.takeWhile { it.isDigit() }.toIntOrNull()
                    val content = trim.substringAfter(' ').let {
                        // "1. text" -> drop "1." already consumed via takeWhile+dot?
                        trim.replaceFirst(Regex("""^\d+[.)]\s*"""), "")
                    }
                    blocks.add(PdfBlock.Bullet(parseSpans(content), number)); i++
                }
                IMAGE_REF.containsMatchIn(trim) && trim.startsWith("![") -> {
                    val match = IMAGE_REF.find(trim)!!
                    val key = match.groupValues[2].substringAfterLast("/").substringBefore("?")
                    blocks.add(PdfBlock.Image(key, match.groupValues[1]))
                    val rest = trim.replaceFirst(IMAGE_REF, "").trim()
                    if (rest.isNotEmpty()) blocks.add(PdfBlock.Para(parseSpans(rest)))
                    i++
                }
                else -> {
                    val stripped = if (trim.startsWith("> ")) trim.removePrefix("> ") else trim
                    blocks.add(PdfBlock.Para(parseSpans(stripped))); i++
                }
            }
        }
        return blocks
    }

    internal fun splitTableRow(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    // ── PDF rendering (Android) ──────────────────────────────────────

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN_SIDE = 56
    private const val MARGIN_TOP = 64
    private const val MARGIN_BOTTOM = 76
    private const val INK = 0xFF212121.toInt()
    private const val ACCENT = 0xFF1B5E20.toInt()
    private const val MUTED = 0xFF757575.toInt()
    private const val BAND = 0xFFEDF2ED.toInt()
    private const val GRID = 0xFFBDBDBD.toInt()

    internal class Paints {
        val title = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 24f
            color = INK; isAntiAlias = true
        }
        val h1 = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 19f
            color = INK; isAntiAlias = true
        }
        val h2 = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 16f
            color = INK; isAntiAlias = true
        }
        val h3 = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 13.5f
            color = INK; isAntiAlias = true
        }
        val body = Paint().apply {
            typeface = Typeface.DEFAULT; textSize = 11.5f; color = INK; isAntiAlias = true
        }
        val bodyBold = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 11.5f
            color = INK; isAntiAlias = true
        }
        val code = Paint().apply {
            typeface = Typeface.MONOSPACE; textSize = 10.5f; color = 0xFF37474F.toInt()
            isAntiAlias = true
        }
        val caption = Paint().apply {
            typeface = Typeface.DEFAULT; textSize = 9.5f; color = MUTED; isAntiAlias = true
        }
        val footer = Paint().apply {
            typeface = Typeface.DEFAULT; textSize = 9f; color = MUTED; isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val band = Paint().apply { color = BAND; style = Paint.Style.FILL }
        val grid = Paint().apply { color = GRID; style = Paint.Style.STROKE; strokeWidth = 1f }
        val rule = Paint().apply { color = ACCENT; style = Paint.Style.STROKE; strokeWidth = 2f }
    }

    private fun paintFor(span: TextSpan, paints: Paints): Paint = when {
        span.code -> paints.code
        span.bold -> paints.bodyBold
        else -> paints.body
    }

    /** Greedy word wrap preserving per-span paints. */
    internal fun layoutSpans(
        spans: List<TextSpan>,
        paints: Paints,
        maxWidthPx: Int,
    ): List<List<Pair<String, Paint>>> {
        val words = ArrayList<Pair<String, Paint>>()
        for (span in spans) {
            val paint = paintFor(span, paints)
            for (word in span.text.split(' ')) {
                if (word.isNotEmpty()) words.add(word to paint)
            }
        }
        val lines = ArrayList<List<Pair<String, Paint>>>()
        var current = ArrayList<Pair<String, Paint>>()
        var width = 0f
        for ((word, paint) in words) {
            val wordWidth = paint.measureText("$word ")
            if (width + wordWidth <= maxWidthPx || current.isEmpty()) {
                current.add(word to paint)
                width += wordWidth
            } else {
                lines.add(current)
                current = arrayListOf(word to paint)
                width = wordWidth
            }
        }
        if (current.isNotEmpty() || lines.isEmpty()) lines.add(current)
        return lines
    }

    /**
     * Render [markdown] as a paginated PDF report into [file].
     * [images] maps `![alt](src)` filenames to decoded bitmaps (resolved by the
     * caller from cache/SAF); missing keys render as a caption line instead of
     * breaking the document. Returns the page count.
     */
    fun savePdf(
        file: File,
        title: String,
        markdown: String,
        images: Map<String, Bitmap> = emptyMap(),
    ): Int {
        val paints = Paints()
        val contentW = PAGE_W - 2 * MARGIN_SIDE
        val blocks = parseMarkdownBlocks(title, markdown)
        val document = PdfDocument()
        var pageNum = 0
        var page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        pageNum = 1
        var y = MARGIN_TOP.toFloat()
        var capped = false
        fun newPage() {
            drawFooter(page, paints, pageNum)
            document.finishPage(page)
            if (pageNum >= MAX_PDF_PAGES) {
                capped = true
                return
            }
            pageNum++
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNum).create())
            y = MARGIN_TOP.toFloat()
        }
        fun need(height: Float) {
            if (!capped && y + height > PAGE_H - MARGIN_BOTTOM) newPage()
        }
        val currentPage: () -> PdfDocument.Page = { page }
        try {
            for (block in blocks) {
                if (capped) break
                when (block) {
                    is PdfBlock.Title -> {
                        need(paints.title.textSize * 1.6f + 14f)
                        page.canvas.drawText(block.text, MARGIN_SIDE.toFloat(), y + paints.title.textSize, paints.title)
                        y += paints.title.textSize * 1.6f
                        page.canvas.drawLine(
                            MARGIN_SIDE.toFloat(), y, (PAGE_W - MARGIN_SIDE).toFloat(), y, paints.rule,
                        )
                        y += 14f
                    }
                    is PdfBlock.Heading -> {
                        val paint = when (block.level) {
                            1 -> paints.h1
                            2 -> paints.h2
                            else -> paints.h3
                        }
                        val lines = layoutSpans(listOf(TextSpan(block.text)), paints, contentW)
                        for (line in lines) {
                            if (capped) break
                            need(paint.textSize * 1.5f)
                            drawLine(page, line, MARGIN_SIDE.toFloat(), y + paint.textSize, paint, paints)
                            y += paint.textSize * 1.5f
                        }
                        y += 4f
                    }
                    is PdfBlock.Para -> y = drawPara(currentPage, paints, block.spans, contentW, 0f, y,
                        onNeed = { need(it) }, isCapped = { capped })
                    is PdfBlock.Bullet -> {
                        val marker = block.number?.let { "$it." } ?: "•"
                        val markerW = paints.body.measureText("$marker  ")
                        y = drawPara(currentPage, paints, block.spans, (contentW - 18 - markerW).toInt(),
                            18f + markerW, y,
                            onNeed = { need(it) }, isCapped = { capped },
                            marker = marker to markerW)
                    }
                    is PdfBlock.Table -> y = drawTable(currentPage, paints, block, contentW, y,
                        onNewPage = { newPage(); y = MARGIN_TOP.toFloat() },
                        isCapped = { capped })
                    is PdfBlock.Image -> {
                        val bitmap = images[block.key]
                        if (bitmap == null || bitmap.isRecycled) {
                            need(paints.caption.textSize * 1.5f)
                            val note = "[image missing: ${block.alt.ifBlank { block.key }}]"
                            page.canvas.drawText(note, MARGIN_SIDE.toFloat(), y + paints.caption.textSize, paints.caption)
                            y += paints.caption.textSize * 1.8f
                        } else {
                            val scale = (contentW.toFloat() / bitmap.width)
                                .coerceAtMost((PAGE_H - MARGIN_TOP - MARGIN_BOTTOM - 40) / bitmap.height.toFloat())
                                .coerceAtMost(1f)
                            val w = bitmap.width * scale
                            val h = bitmap.height * scale
                            need(h + paints.caption.textSize * 2.2f)
                            val left = MARGIN_SIDE + (contentW - w) / 2
                            val dst = android.graphics.RectF(left, y, left + w, y + h)
                            page.canvas.drawBitmap(bitmap, null, dst, Paint().apply { isAntiAlias = true; isFilterBitmap = true })
                            y += h + 4f
                            val caption = block.alt.ifBlank { block.key }
                            page.canvas.drawText(caption, PAGE_W / 2f, y + paints.caption.textSize, paints.caption.apply { textAlign = Paint.Align.CENTER })
                            paints.caption.textAlign = Paint.Align.LEFT
                            y += paints.caption.textSize * 2.2f
                        }
                    }
                    PdfBlock.Divider -> {
                        need(18f)
                        y += 6f
                        page.canvas.drawLine(
                            MARGIN_SIDE.toFloat(), y, (PAGE_W - MARGIN_SIDE).toFloat(), y, paints.grid,
                        )
                        y += 12f
                    }
                    PdfBlock.Gap -> y += 6f
                }
            }
            drawFooter(page, paints, pageNum)
            document.finishPage(page)
            FileOutputStream(file).use { document.writeTo(it) }
            return pageNum
        } finally {
            document.close()
        }
    }

    private fun drawLine(
        page: PdfDocument.Page,
        line: List<Pair<String, Paint>>,
        x: Float,
        baseline: Float,
        default: Paint,
        paints: Paints,
    ) {
        var cursor = x
        if (line.isEmpty()) {
            page.canvas.drawText(" ", cursor, baseline, default)
            return
        }
        for ((word, paint) in line) {
            page.canvas.drawText(word, cursor, baseline, paint)
            cursor += paint.measureText("$word ")
        }
    }

    private fun drawPara(
        pageProvider: () -> PdfDocument.Page,
        paints: Paints,
        spans: List<TextSpan>,
        contentW: Int,
        indent: Float,
        startY: Float,
        onNeed: (Float) -> Unit,
        isCapped: () -> Boolean,
        marker: Pair<String, Float>? = null,
    ): Float {
        var y = startY
        val lines = layoutSpans(spans, paints, contentW)
        for ((index, line) in lines.withIndex()) {
            if (isCapped()) break
            onNeed(paints.body.textSize * 1.5f)
            if (marker != null && index == 0) {
                pageProvider().canvas.drawText(
                    marker.first, MARGIN_SIDE.toFloat() + indent - marker.second - 6f,
                    y + paints.body.textSize, paints.body,
                )
            }
            drawLine(pageProvider(), line, MARGIN_SIDE.toFloat() + indent, y + paints.body.textSize, paints.body, paints)
            y += paints.body.textSize * 1.5f
        }
        return y + 3f
    }

    private fun drawTable(
        pageProvider: () -> PdfDocument.Page,
        paints: Paints,
        table: PdfBlock.Table,
        contentW: Int,
        startY: Float,
        onNewPage: () -> Unit,
        isCapped: () -> Boolean,
    ): Float {
        var y = startY
        val cols = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
        val colW = contentW.toFloat() / cols
        fun rowHeight(cells: List<String>, paint: Paint): Float {
            var h = paint.textSize * 1.5f
            for (cell in cells) {
                val maxW = (colW - 10).toInt().coerceAtLeast(20)
                val lines = layoutSpans(parseSpans(cell), paints, maxW)
                h = maxOf(h, lines.size * paint.textSize * 1.5f)
            }
            return h + 8f
        }
        fun drawHeaderRow(atY: Float): Float {
            var y = atY
            // Header cells wrap like body cells so long titles never overlap.
            var h = paints.bodyBold.textSize * 1.5f
            for (cell in table.header) {
                val maxW = (colW - 10).toInt().coerceAtLeast(20)
                val lines = layoutSpans(parseSpans(cell), paints, maxW)
                h = maxOf(h, lines.size * paints.bodyBold.textSize * 1.5f)
            }
            h += 8f
            if (y + h > PAGE_H - MARGIN_BOTTOM) {
                onNewPage()
                y = MARGIN_TOP.toFloat()
            }
            val page = pageProvider()
            page.canvas.drawRect(
                MARGIN_SIDE.toFloat(), y, (MARGIN_SIDE + contentW).toFloat(), y + h, paints.band,
            )
            for (index in 0 until cols) {
                val cell = table.header.getOrElse(index) { "" }
                val maxW = (colW - 10).toInt().coerceAtLeast(20)
                val lines = layoutSpans(parseSpans(cell), paints, maxW)
                var cy = y + 4f + paints.bodyBold.textSize
                for (line in lines) {
                    val boldLine = line.map { (word, _) -> word to paints.bodyBold }
                    drawLine(page, boldLine, MARGIN_SIDE + index * colW + 5f, cy, paints.bodyBold, paints)
                    cy += paints.bodyBold.textSize * 1.5f
                }
                page.canvas.drawRect(
                    MARGIN_SIDE + index * colW, y,
                    MARGIN_SIDE + (index + 1) * colW, y + h, paints.grid,
                )
            }
            return y + h
        }
        fun drawRow(cells: List<String>, paint: Paint, fill: Paint?, height: Float) {
            if (y + height > PAGE_H - MARGIN_BOTTOM) {
                onNewPage()
                y = MARGIN_TOP.toFloat()
                // Repeat the header on the new page.
                y = drawHeaderRow(y)
            }
            val page = pageProvider()
            fill?.let {
                page.canvas.drawRect(
                    MARGIN_SIDE.toFloat(), y, (MARGIN_SIDE + contentW).toFloat(), y + height, it,
                )
            }
            for (index in 0 until cols) {
                val cell = cells.getOrElse(index) { "" }
                val maxW = (colW - 10).toInt().coerceAtLeast(20)
                val lines = layoutSpans(parseSpans(cell), paints, maxW)
                var cy = y + 4f + paint.textSize
                for (line in lines) {
                    drawLine(page, line, MARGIN_SIDE + index * colW + 5f, cy, paint, paints)
                    cy += paint.textSize * 1.5f
                }
                page.canvas.drawRect(
                    MARGIN_SIDE + index * colW, y,
                    MARGIN_SIDE + (index + 1) * colW, y + height, paints.grid,
                )
            }
            y += height
        }
        y = drawHeaderRow(y)
        var stripe = false
        for (row in table.rows) {
            if (isCapped()) break
            drawRow(row, paints.body, if (stripe) paints.band else null, rowHeight(row, paints.body))
            stripe = !stripe
        }
        return y + 8f
    }

    private fun drawFooter(page: PdfDocument.Page, paints: Paints, pageNum: Int) {
        page.canvas.drawText(
            "Page $pageNum", PAGE_W / 2f, (PAGE_H - 36).toFloat(), paints.footer,
        )
    }

    /**
     * Write [bytes] as [fileName] into the SAF [treeUri]. Returns the document URI
     * string, or null when the grant is gone / creation fails (caller falls back).
     */
    fun writeToWorkspace(
        resolver: ContentResolver,
        treeUri: Uri,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): String? {
        require(!fileName.contains("..") && !fileName.contains("/")) { "Unsafe file name" }
        checkContentSize(bytes)
        return try {
            val treeDoc = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri),
            )
            val docUri = DocumentsContract.createDocument(resolver, treeDoc, mimeType, fileName)
                ?: return null
            resolver.openOutputStream(docUri, "w")?.use { it.write(bytes) }
                ?: return null
            docUri.toString()
        } catch (_: Exception) {
            null
        }
    }

    /** Validate a persisted workspace tree URI string (JVM-safe: no Uri class). */
    fun workspaceTreeUri(raw: String): String? {
        if (raw.isBlank()) return null
        return raw.takeIf { it.startsWith("content://") }
    }

    /** Best-effort re-assertion of a persisted workspace grant. False = ask user again. */
    fun hasWorkspaceGrant(context: Context, treeUri: Uri): Boolean {
        val grants = context.contentResolver.persistedUriPermissions
        return grants.any {
            it.uri == treeUri && it.isWritePermission && it.isReadPermission
        }
    }
}

/** Persist the workspace SAF grant taken by the folder picker (mirrors auto-backup). */
fun takeWorkspaceGrant(context: Context, uri: Uri) {
    context.contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
}
