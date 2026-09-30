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
 * else app `cacheDir/artifacts` (private, shareable via the next chat export).
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
                    "Files go to the user's Agent workspace folder when set, else app storage.",
                parameters = ToolParameters(
                    properties = mapOf(
                        "title" to ToolProperty("string", "Report title (also used for the file name)."),
                        "format" to ToolProperty("string", "File format: 'md' for Markdown, 'pdf' for a rendered PDF report."),
                        "content" to ToolProperty("string", "Full Markdown content of the report."),
                        "filename" to ToolProperty("string", "Optional explicit file name (must end in .md or .pdf)."),
                    ),
                    required = listOf("title", "format", "content"),
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
                val tmp = File.createTempFile("agora_artifact", ".pdf", app.cacheDir)
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
                }.toString()
            }
            val dir = File(app.cacheDir, "artifacts").also { if (!it.exists()) it.mkdirs() }
            val out = ByteArrayOutputStream().also { it.write(bytes) }
            File(dir, fileName).outputStream().use { out.writeTo(it) }
            buildJsonObject {
                put("type", "artifact")
                put("format", format)
                put("fileName", fileName)
                put("sizeBytes", bytes.size)
                put("saved_to", "app storage (pick an Agent workspace folder in Settings to choose where files go)")
                put("path", File(dir, fileName).absolutePath)
            }.toString()
        } catch (e: Exception) {
            errorJson("write_error", e.message.orEmpty())
        }
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
        val cacheDir = File(app.cacheDir, "artifacts")
        val treeUriString = ArtifactExporter.workspaceTreeUri(ctx.agentWorkspaceUri)
        val treeUri = treeUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val useSaf = treeUri != null && ArtifactExporter.hasWorkspaceGrant(app, treeUri)
        for (key in ArtifactExporter.imageKeys(content)) {
            try {
                val cached = File(cacheDir, key)
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
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri),
        )
        var docUri: Uri? = null
        app.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) == fileName) {
                    docUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, cursor.getString(idCol),
                    )
                    break
                }
            }
        }
        val uri = docUri ?: return null
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
    ): String? {
        ArtifactExporter.checkContentSize(bytes)
        val treeUriString = ArtifactExporter.workspaceTreeUri(ctx.agentWorkspaceUri)
        val treeUri = treeUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (treeUri != null && ArtifactExporter.hasWorkspaceGrant(app, treeUri)) {
            val docUri = ArtifactExporter.writeToWorkspace(
                app.contentResolver, treeUri, fileName, mime, bytes,
            )
            if (docUri != null) return docUri
        }
        val dir = File(app.cacheDir, "artifacts").also { if (!it.exists()) it.mkdirs() }
        File(dir, fileName).outputStream().use { it.write(bytes) }
        return null
    }

    private fun errorJson(code: String, message: String = ""): String = buildJsonObject {
        put("type", "artifact")
        put("error", code)
        if (message.isNotBlank()) put("message", message)
    }.toString()
}
