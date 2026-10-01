package com.newoether.agora.tool

import android.app.Application
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import com.newoether.agora.api.HttpClient
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.api.typesafe.AnswerGuard
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Agent artifact tool (`save_artifact`): writes Markdown reports and rendered PDF
 * files. Offered only in agent `build` mode.
 *
 * Destination: the user's Agent workspace folder (SAF) when set and granted,
 * else app `filesDir/artifacts` (private, persistent — not the cache dir,
 * which Android may clear; shareable via the next chat export).
 * Content is guard-cleaned and capped; filenames are sanitized; writes can never
 * escape the destination directory.
 */
class ArtifactToolProvider(private val app: Application) : ToolProvider {

    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (ctx.agentMode != "build") return emptyList()
        return listOf(
            ToolDefinition(function = ToolFunction(
                name = "save_artifact",
                description = "Save a Markdown report or PDF file for the user (research reports, guides, documentation). " +
                    "Use this when the user asks for a file, report, PDF, or document. " +
                    "Markdown supports headings, bold, bullets, numbered lists, tables and " +
                    "`![caption](filename)` images (download them first with fetch_image). " +
                    "RENDERER LIMITS: format 'pdf' renders Markdown with the app's built-in " +
                    "renderer (simple layouts only — no embedded HTML/CSS, no complex tables). " +
                    "There is NO built-in PowerPoint renderer: to deliver a .pptx, build it " +
                    "yourself in the sandbox with python-pptx (pure Python, no numpy/matplotlib " +
                    "needed; if pip refuses, use a venv), save it to the shared folder, then " +
                    "register it with source_path. The same source_path flow works for any " +
                    "prebuilt file (PDFs from reportlab, spreadsheets, etc.). " +
                    "If you already built the file yourself in the sandbox (e.g. a PDF generated " +
                    "with a Python library and saved to the shared folder), pass its file name as " +
                    "`source_path` instead of `content` and it will be registered as-is. " +
                    "Files go to the user's Agent workspace folder when set, else app storage. " +
                    "The result reports format, sizeBytes and saved_to so you can confirm delivery.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "title" to ToolProperty("string", "Report title (also used for the file name)."),
                        "format" to ToolProperty("string", "File format: 'md' for Markdown, 'pdf' for a rendered PDF report. Ignored when source_path is set (format comes from the file)."),
                        "content" to ToolProperty("string", "Full Markdown content of the report. Not needed when source_path is set."),
                        "filename" to ToolProperty("string", "Optional explicit file name (must end in .md or .pdf)."),
                        "source_path" to ToolProperty(
                            "string",
                            "Optional file name of an already-built file in the agent workspace " +
                                "(e.g. 'report.pdf', 'slides.pptx') to register as-is instead of " +
                                "rendering from content. Use this for PPTX files you generated " +
                                "yourself in the sandbox (python-pptx) and for PDFs that need " +
                                "layouts the built-in renderer cannot do.",
                        ),
                    ),
                    required = listOf("title"),
                ),
            )),
            ToolDefinition(function = ToolFunction(
                name = "fetch_image",
                description = "Download an image from the web into the artifact workspace so it " +
                    "can be embedded in notes and PDFs via `![caption](filename)`. Verifies " +
                    "the file really decodes as an image (width, height reported); failures " +
                    "come back as errors — retry with another URL instead of referencing it.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "url" to ToolProperty("string", "Direct image URL (png/jpg/webp)."),
                        "filename" to ToolProperty(
                            "string",
                            "Optional file name (must end in .png/.jpg/.jpeg/.webp). " +
                                "Defaults to the URL file name.",
                        ),
                    ),
                    required = listOf("url"),
                ),
            )),
        )
    }

    override fun handles(name: String): Boolean = name == "save_artifact" || name == "fetch_image"

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        if (name == "fetch_image") return@withContext executeFetchImage(arguments, ctx)
        if (name != "save_artifact") return@withContext "Unknown tool: $name"
        try {
            val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(
                arguments.ifBlank { "{}" },
            )
            fun str(key: String): String? =
                (args[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            val title = str("title") ?: return@withContext errorJson("no_title")
            val sourcePath = str("source_path")
            if (sourcePath != null) {
                return@withContext executeSourcePath(sourcePath, str("filename"), ctx)
            }
            val format = (str("format") ?: "md").lowercase()
            if (format != "md" && format != "pdf") return@withContext errorJson("bad_format")
            val content = AnswerGuard.cleanForSynthesis(
                str("content") ?: return@withContext errorJson("no_content"),
                ArtifactExporter.MAX_MARKDOWN_BYTES,
            )
            val ext = format
            val fileName = (str("filename")?.takeIf {
                it.lowercase().endsWith(".$ext") && !it.contains("..") && !it.contains("/")
            } ?: ArtifactExporter.sanitizeFileName(title, ext))

            val bytes: ByteArray
            val mime: String
            if (format == "pdf") {
                val tmp = File.createTempFile("agentx_artifact", ".pdf", app.cacheDir)
                try {
                    val images = resolvePdfImages(content, ctx)
                    ArtifactExporter.savePdf(tmp, title, content, images)
                    images.values.forEach { if (!it.isRecycled) it.recycle() }
                    bytes = tmp.readBytes()
                } finally {
                    tmp.delete()
                }
                mime = "application/pdf"
            } else {
                bytes = content.toByteArray(Charsets.UTF_8)
                mime = "text/markdown"
            }
            ArtifactExporter.checkContentSize(bytes)

            // Workspace first, app storage fallback.
            val treeUriString = ArtifactExporter.workspaceTreeUri(ctx.agentWorkspaceUri)
            val treeUri = treeUriString?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() }
            val docUri = if (treeUri != null && ArtifactExporter.hasWorkspaceGrant(app, treeUri)) {
                ArtifactExporter.writeToWorkspace(app.contentResolver, treeUri, fileName, mime, bytes)
            } else {
                null
            }
            if (docUri != null) {
                return@withContext buildJsonObject {
                    put("type", "artifact")
                    put("format", format)
                    put("fileName", fileName)
                    put("sizeBytes", bytes.size)
                    put("saved_to", "workspace")
                    put("uri", docUri)
                    if (format == "pdf") put("render", "builtin")
                }.toString()
            }
            val dir = File(app.filesDir, "artifacts").also { if (!it.exists()) it.mkdirs() }
            val out = ByteArrayOutputStream().also { it.write(bytes) }
            File(dir, fileName).outputStream().use { out.writeTo(it) }
            buildJsonObject {
                put("type", "artifact")
                put("format", format)
                put("fileName", fileName)
                put("sizeBytes", bytes.size)
                put("saved_to", "app storage (pick an Agent workspace folder in Settings to choose where files go)")
                put("path", File(dir, fileName).absolutePath)
                if (format == "pdf") put("render", "builtin")
            }.toString()
        } catch (e: Exception) {
            errorJson("write_error", e.message.orEmpty())
        }
    }

    /**
     * Register an already-built file from the agent workspace as an artifact as-is.
     * The agent builds files in the sandbox (e.g. a PDF rendered with a Python
     * library) and writes them to the shared folder; this copies the bytes into the
     * artifact destination (workspace SAF or app storage) so the file reaches the user.
     * Only a plain file name is accepted — no paths, no traversal.
     */
    private suspend fun executeSourcePath(
        sourcePath: String,
        filenameOverride: String?,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        val trimmed = sourcePath.trim()
        val name = trimmed.substringAfterLast('/').substringAfterLast('\\')
        if (name.isBlank() || name.contains("..") || name != trimmed) {
            return@withContext errorJson(
                "bad_source_path",
                "source_path must be a plain file name in the agent workspace (e.g. 'report.pdf')",
            )
        }
        val bytes = readSourceBytes(name, ctx)
            ?: return@withContext errorJson(
                "source_not_found",
                "No file named '$name' in the agent workspace. Save it to the shared folder first.",
            )
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = sourceMime(ext)
        val fileName = filenameOverride?.takeIf {
            it.lowercase().endsWith(".$ext") && !it.contains("..") && !it.contains("/")
        } ?: name
        return@withContext try {
            val docUri = writeArtifactBytes(
                ctx, fileName, mime, bytes,
                maxBytes = ArtifactExporter.MAX_SOURCE_BYTES,
            )
            if (docUri != null) {
                buildJsonObject {
                    put("type", "artifact")
                    put("format", ext.ifBlank { "bin" })
                    put("fileName", fileName)
                    put("sizeBytes", bytes.size)
                    put("saved_to", "workspace")
                    put("uri", docUri)
                    put("source", "source_path")
                    put("render", "prebuilt")
                }.toString()
            } else {
                buildJsonObject {
                    put("type", "artifact")
                    put("format", ext.ifBlank { "bin" })
                    put("fileName", fileName)
                    put("sizeBytes", bytes.size)
                    put("saved_to", "app storage (pick an Agent workspace folder in Settings to choose where files go)")
                    put("source", "source_path")
                    put("render", "prebuilt")
                }.toString()
            }
        } catch (e: IllegalArgumentException) {
            errorJson("source_too_large", e.message.orEmpty())
        }
    }

    /** Read a workspace file by display name: app artifacts dir first, then SAF tree. */
    private fun readSourceBytes(fileName: String, ctx: GenerationContext): ByteArray? {
        // Guard the local-file probe: a broken filesDir (or a File double without an
        // initialized path, as in unit tests) must degrade to "not found" rather than
        // surfacing as a misleading write_error from the outer catch.
        val cached = runCatching { File(app.filesDir, "artifacts/$fileName") }.getOrNull()
        if (cached != null && cached.exists() && cached.isFile) {
            return runCatching { cached.readBytes() }.getOrNull()
        }
        val treeUriString = ArtifactExporter.workspaceTreeUri(ctx.agentWorkspaceUri)
        val treeUri = treeUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (treeUri != null && ArtifactExporter.hasWorkspaceGrant(app, treeUri)) {
            val docUri = findWorkspaceDocument(treeUri, fileName) ?: return null
            return runCatching {
                app.contentResolver.openInputStream(docUri)?.use { it.readBytes() }
            }.getOrNull()
        }
        return null
    }

    /** Find a document by display name directly under the workspace tree. */
    private fun findWorkspaceDocument(treeUri: Uri, fileName: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        app.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) == fileName) {
                    return DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, cursor.getString(idCol),
                    )
                }
            }
        }
        return null
    }

    private fun sourceMime(ext: String): String = when (ext) {
        "pdf" -> "application/pdf"
        "md", "markdown", "txt" -> "text/markdown"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        else -> "application/octet-stream"
    }

    /**
     * Download + verify an image into the artifact destination. Verification is
     * strict (decodable, sane dimensions) so PDFs/MDs never reference dead
     * images — failures must make the model pick another URL.
     */
    private suspend fun executeFetchImage(arguments: String, ctx: GenerationContext): String {
        try {
            val args = Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(
                arguments.ifBlank { "{}" },
            )
            fun str(key: String): String? =
                (args[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            val url = str("url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: return errorJson("bad_url")
            val ext = url.substringAfterLast(".", "").substringBefore("?").lowercase()
                .takeIf { it in setOf("png", "jpg", "jpeg", "webp") } ?: "jpg"
            val fileName = (str("filename")?.takeIf {
                val lower = it.lowercase()
                (lower.endsWith(".png") || lower.endsWith(".jpg") ||
                    lower.endsWith(".jpeg") || lower.endsWith(".webp")) &&
                    !it.contains("..") && !it.contains("/")
            } ?: ArtifactExporter.sanitizeFileName(
                url.substringAfterLast("/").substringBefore("?").substringBeforeLast(".")
                    .takeIf { it.isNotBlank() } ?: "image",
                ext,
            ))
            val bytes = HttpClient.getBytes(
                url,
                mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"),
                callTimeoutMillis = 30_000L,
                readTimeoutMillis = 30_000L,
            )?.takeIf { it.size <= ArtifactExporter.MAX_IMAGE_BYTES && it.isNotEmpty() }
                ?: return errorJson("download_failed")
            // Verify: must decode with sane dimensions.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth > 8000 || bounds.outHeight > 8000
            ) {
                return errorJson("not_an_image")
            }
            val docUri = writeArtifactBytes(ctx, fileName, imageMime(ext), bytes)
            val result = buildJsonObject {
                put("type", "image")
                put("fileName", fileName)
                put("width", bounds.outWidth)
                put("height", bounds.outHeight)
                put("sizeBytes", bytes.size)
                if (docUri != null) {
                    put("saved_to", "workspace")
                    put("uri", docUri)
                } else {
                    put("saved_to", "app storage")
                }
            }
            return result.toString()
        } catch (e: Exception) {
            return errorJson("fetch_error", e.message.orEmpty())
        }
    }

    private fun imageMime(ext: String): String = when (ext) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    /** Resolve `![alt](src)` filenames to bitmaps (cache dir first, SAF second). */
    internal fun resolvePdfImages(content: String, ctx: GenerationContext): Map<String, Bitmap> {
        val out = LinkedHashMap<String, Bitmap>()
        val artifactsDir = File(app.filesDir, "artifacts")
        val treeUriString = ArtifactExporter.workspaceTreeUri(ctx.agentWorkspaceUri)
        val treeUri = treeUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val useSaf = treeUri != null && ArtifactExporter.hasWorkspaceGrant(app, treeUri)
        for (key in ArtifactExporter.imageKeys(content)) {
            try {
                val cached = File(artifactsDir, key)
                val bitmap = if (cached.exists()) {
                    BitmapFactory.decodeFile(cached.absolutePath)
                } else if (useSaf) {
                    readWorkspaceImage(treeUri!!, key)
                } else {
                    null
                }
                if (bitmap != null && !bitmap.isRecycled &&
                    bitmap.width > 0 && bitmap.height > 0
                ) {
                    out[key] = bitmap
                }
            } catch (_: Exception) {
                // Missing/unreadable images render as caption lines; never crash.
            }
        }
        return out
    }

    private fun readWorkspaceImage(treeUri: Uri, fileName: String): Bitmap? {
        val uri = findWorkspaceDocument(treeUri, fileName) ?: return null
        return app.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            if (bytes.size > ArtifactExporter.MAX_IMAGE_BYTES) return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    /** Shared destination logic: SAF workspace first, cache fallback. */
    internal fun writeArtifactBytes(
        ctx: GenerationContext,
        fileName: String,
        mime: String,
        bytes: ByteArray,
        maxBytes: Int = ArtifactExporter.MAX_MARKDOWN_BYTES,
    ): String? {
        require(bytes.size <= maxBytes) {
            "Artifact content too large (${bytes.size} bytes, max $maxBytes)"
        }
        val treeUriString = ArtifactExporter.workspaceTreeUri(ctx.agentWorkspaceUri)
        val treeUri = treeUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (treeUri != null && ArtifactExporter.hasWorkspaceGrant(app, treeUri)) {
            val docUri = ArtifactExporter.writeToWorkspace(
                app.contentResolver, treeUri, fileName, mime, bytes,
            )
            if (docUri != null) return docUri
        }
        val dir = File(app.filesDir, "artifacts").also { if (!it.exists()) it.mkdirs() }
        File(dir, fileName).outputStream().use { it.write(bytes) }
        return null
    }

    private fun errorJson(code: String, message: String = ""): String = buildJsonObject {
        put("type", "artifact")
        put("error", code)
        if (message.isNotBlank()) put("message", message)
    }.toString()
}
