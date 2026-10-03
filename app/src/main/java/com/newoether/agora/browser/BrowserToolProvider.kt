package com.newoether.agora.browser

import android.os.SystemClock
import com.newoether.agora.api.ToolDefinition
import com.newoether.agora.api.ToolFunction
import com.newoether.agora.api.ToolParameters
import com.newoether.agora.api.ToolProperty
import com.newoether.agora.browser.cdp.CdpException
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.tool.ToolExecutionEvent
import com.newoether.agora.tool.ToolExecutionResult
import com.newoether.agora.tool.ToolImageStore
import com.newoether.agora.tool.ToolPresentationMetadata
import com.newoether.agora.tool.ToolProvider
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Agent tools for the on-device / tunneled browser (§1.3, tools component).
 *
 * Tools: `browser_navigate`, `browser_snapshot` (compact `@eN` refs from the AX
 * tree), `browser_click`, `browser_fill` (text OR `cred_id`), `browser_key`,
 * `browser_scroll`, `browser_screenshot`, `browser_takeover`,
 * `browser_download_status`.
 *
 * Every tool action emits a structured `browser` diagnostic event (action +
 * duration + outcome) via [BrowserDiagnostics] — the browser audit trail.
 * Privacy: full URLs are never logged (host only), credentials and page text
 * never enter diagnostics or results.
 *
 * `browser_fill` with `cred_id` resolves the secret through [credentialResolver]
 * (owned by the credential-vault stream when it lands; until then the caller
 * supplies it) and types it via the trusted `Input.insertText` path — the
 * value never enters model context, logs, or diagnostics.
 */
class BrowserToolProvider(
    private val registry: BrowserSessionRegistry,
    private val prefs: BrowserPreferenceStore,
    private val imageStore: ToolImageStore,
    private val credentialResolver: suspend (credId: String) -> String? = { null },
) : ToolProvider {

    private val json = Json { ignoreUnknownKeys = true }


    override fun definitions(ctx: GenerationContext): List<ToolDefinition> {
        if (!prefs.browserEnabled.value) return emptyList()
        return listOf(
            tool(
                "browser_navigate",
                "Navigate the browser to a URL. The page loads first; use browser_snapshot " +
                    "afterwards to see what is on it.",
                mapOf("url" to ToolProperty("string", "The http(s) URL to open.")),
                listOf("url"),
            ),
            tool(
                "browser_snapshot",
                "Capture the page's accessibility tree as compact @eN references " +
                    "(viewport-relevant, truncated). Use the refs with browser_click / browser_fill.",
                emptyMap(),
                emptyList(),
            ),
            tool(
                "browser_click",
                "Click the element with the given @eN reference. " +
                    "The result already includes a fresh page snapshot with new @eN refs - " +
                    "do NOT call browser_snapshot after; keep acting on the returned refs.",
                mapOf("ref" to ToolProperty("string", "Element reference, e.g. \"@e3\".")),
                listOf("ref"),
            ),
            tool(
                "browser_fill",
                "Type into the element with the given @eN reference. Pass text directly, " +
                    "or a vault credential id as cred_id (the secret is typed without ever " +
                    "entering the conversation). The result already includes a fresh page " +
                    "snapshot - do NOT call browser_snapshot after.",
                mapOf(
                    "ref" to ToolProperty("string", "Element reference, e.g. \"@e3\"."),
                    "text" to ToolProperty("string", "Text to type. Omit when using cred_id."),
                    "cred_id" to ToolProperty("string", "Vault credential id to type. Omit when using text."),
                ),
                listOf("ref"),
            ),
            tool(
                "browser_key",
                "Press a key: Enter, Tab, Escape, Backspace, Delete, ArrowUp/Down/Left/Right, " +
                    "or a single character. The result already includes a fresh page " +
                    "snapshot - do NOT call browser_snapshot after.",
                mapOf("key" to ToolProperty("string", "Key name, e.g. \"Enter\".")),
                listOf("key"),
            ),
            tool(
                "browser_scroll",
                "Scroll the page with the mouse wheel. The result already includes a fresh " +
                    "page snapshot - do NOT call browser_snapshot after.",
                mapOf(
                    "direction" to ToolProperty("string", "up, down (default), left or right."),
                    "pixels" to ToolProperty("integer", "Scroll distance in pixels (default 400)."),
                ),
                emptyList(),
            ),
            tool(
                "browser_screenshot",
                "Capture a viewport JPEG screenshot. Reserved for hard pages (canvas, seat " +
                    "maps) that the accessibility tree cannot describe.",
                emptyMap(),
                emptyList(),
            ),
            tool(
                "browser_takeover",
                "Hand browser control to the user (take-over mode): the agent loop pauses " +
                    "and the user drives the live browser. Action: start, stop, or status.",
                mapOf("action" to ToolProperty("string", "start, stop, or status (default).")),
                emptyList(),
            ),
            tool(
                "browser_download_status",
                "List browser downloads and their progress (files land in the app-owned " +
                    "download dir on the local backend).",
                emptyMap(),
                emptyList(),
            ),
        )
    }

    override fun handles(name: String): Boolean = name in TOOL_NAMES

    override fun presentationMetadata(name: String): ToolPresentationMetadata? =
        if (handles(name)) ToolPresentationMetadata(displayName = "Browser") else null

    override suspend fun execute(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): String = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        // One browser per chat: the session follows the conversation, so two
        // chats never share (or fight over) one page.
        val session = registry.get(ctx.conversationId)
        session.diagnosticContext = ctx
        if (!prefs.browserEnabled.value) {
            return@withContext errorJson(name, "browser_disabled", "Browser tools are disabled.")
        }
        try {
            val outcome = when (name) {
                "browser_navigate" -> navigate(session, arguments, ctx)
                "browser_snapshot" -> snapshot(session, ctx)
                "browser_click" -> click(session, arguments, ctx)
                "browser_fill" -> fill(session, arguments, ctx)
                "browser_key" -> pressKey(session, arguments, ctx)
                "browser_scroll" -> scroll(session, arguments, ctx)
                "browser_screenshot" -> screenshot(session, ctx)
                "browser_takeover" -> takeover(session, arguments)
                "browser_download_status" -> downloadStatus(session)
                else -> return@withContext errorJson(name, "unknown_tool", "Unknown tool: $name")
            }
            BrowserDiagnostics.record(
                ctx, outcome.action,
                SystemClock.elapsedRealtime() - started, outcome.diagnosticOutcome(json), outcome.extra,
            )
            outcome.json
        } catch (e: Exception) {
            // Stable, human-readable error identity. NEVER use
            // e.javaClass.simpleName here: R8 obfuscates it in release builds
            // (e.g. "ja1"), which made every browser failure undiagnosable
            // from logs (III.1). The code below is derived from the message.
            val (code, detail, hint) = errorParts(e)
            val outcome = "error:$code"
            BrowserDiagnostics.record(
                ctx, actionName(name),
                SystemClock.elapsedRealtime() - started, outcome, emptyMap(),
            )
            DebugLog.w(TAG, "$name failed: $code — ${e.message?.take(200)}")
            errorJson(name, code, detail, hint)
        }
    }

    /**
     * Screenshot override: the JPEG is persisted via [ToolImageStore] and
     * attached so the model sees it as a multimodal turn, exactly like
     * `view_image` (transcribeImages = true).
     */
    override fun executeEvents(
        name: String,
        arguments: String,
        ctx: GenerationContext,
    ): Flow<ToolExecutionEvent> = flow {
        if (name == "browser_screenshot" && prefs.browserEnabled.value) {
            val started = SystemClock.elapsedRealtime()
            val session = registry.get(ctx.conversationId)
            session.diagnosticContext = ctx
            try {
                if (!session.ensureConnected()) {
                    emit(ToolExecutionEvent.Completed(ToolExecutionResult(notConnected(name), isError = true)))
                    return@flow
                }
                val result = captureScreenshotResult(session, ctx)
                BrowserDiagnostics.record(
                    ctx, "screenshot",
                    SystemClock.elapsedRealtime() - started, "ok",
                    mapOf("backend" to (session.currentBackendMode()?.persisted ?: "-")),
                )
                emit(ToolExecutionEvent.Completed(result))
            } catch (e: Exception) {
                val (code, detail, hint) = errorParts(e)
                BrowserDiagnostics.record(
                    ctx, "screenshot",
                    SystemClock.elapsedRealtime() - started, "error:$code", emptyMap(),
                )
                emit(ToolExecutionEvent.Completed(ToolExecutionResult(errorJson(name, code, detail, hint), isError = true)))
            }
        } else {
            val resultJson = execute(name, arguments, ctx)
            // isError follows the payload: failure JSON carries "error",
            // success JSON carries "ok":true. The model must see failures
            // as errors, not as ok results.
            emit(ToolExecutionEvent.Completed(ToolExecutionResult(resultJson, isError = isErrorJson(resultJson))))
        }
    }

    // ── tool implementations ──

    private data class ActionOutcome(
        val action: String,
        val json: String,
        val extra: Map<String, String> = emptyMap(),
    ) {
        /**
         * Derive the diagnostic outcome from the payload itself: success JSON
         * carries `"ok": true`, failure JSON carries `"error": "<code>"`.
         * Never hardcode "ok" — a returned outcome can still be a failure.
         */
        fun diagnosticOutcome(parser: Json): String = try {
            val obj = parser.parseToJsonElement(json).jsonObject
            val error = (obj["error"] as? JsonPrimitive)?.contentOrNull
            if (error != null) "error:$error"
            else if ((obj["ok"] as? JsonPrimitive)?.booleanOrNull == true) "ok"
            else "error:unknown"
        } catch (_: Exception) {
            "error:unparseable"
        }
    }

    private fun args(arguments: String): JsonObject =
        try {
            json.parseToJsonElement(arguments.ifBlank { "{}" }) as JsonObject
        } catch (_: Exception) {
            buildJsonObject {}
        }

    private fun argStr(a: JsonObject, name: String): String? =
        (a[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun argInt(a: JsonObject, name: String, default: Int): Int =
        (a[name] as? JsonPrimitive)?.intOrNull ?: default

    private suspend fun navigate(session: BrowserSession, arguments: String, ctx: GenerationContext): ActionOutcome {
        val url = argStr(args(arguments), "url")
            ?: return ActionOutcome("navigate", errorJson("browser_navigate", "no_url", ""))
        val scheme = runCatching { android.net.Uri.parse(url).scheme?.lowercase() }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            return ActionOutcome("navigate", errorJson("browser_navigate", "bad_url", "Only http(s) URLs are allowed."))
        }
        if (!session.ensureConnected()) {
            return ActionOutcome("navigate", notConnected("browser_navigate"))
        }
        val loaded = session.navigate(url, ctx.toolTimeoutMs)
        return ActionOutcome(
            "navigate",
            buildJsonObject {
                put("type", "browser")
                put("tool", "browser_navigate")
                put("ok", true)
                put("loaded", loaded)
            }.toString(),
            mapOf(
                "domain" to hostOf(url),
                "backend" to (session.currentBackendMode()?.persisted ?: "-"),
            ),
        )
    }

    private suspend fun snapshot(session: BrowserSession, ctx: GenerationContext): ActionOutcome {
        if (!session.ensureConnected()) {
            return ActionOutcome("snapshot", notConnected("browser_snapshot"))
        }
        val snap = captureFreshSnapshot(session, ctx) ?: return ActionOutcome(
            "snapshot",
            errorJson("browser_snapshot", "snapshot_failed", "Could not read the page."),
        )
        val truncated = snap.text.length >= SNAPSHOT_MAX_CHARS
        return ActionOutcome(
            "snapshot",
            buildJsonObject {
                put("type", "browser")
                put("tool", "browser_snapshot")
                put("ok", true)
                put("snapshot", snap.text)
                put("truncated", truncated)
            }.toString(),
            mapOf(
                "chars" to snap.text.length.toString(),
                "backend" to (session.currentBackendMode()?.persisted ?: "-"),
            ),
        )
    }

    /**
     * Read the page and refresh the @eN refs. Every mutating action
     * (click/fill/key/scroll) calls this and embeds the fresh snapshot in its
     * own result, so the agent never needs a separate browser_snapshot call
     * after acting — that halves the tool roundtrips in the browser loop
     * (the "fast click" behavior). Null when the page cannot be read.
     */
    private data class FreshSnapshot(val text: String)

    private suspend fun captureFreshSnapshot(session: BrowserSession, ctx: GenerationContext): FreshSnapshot? =
        runCatching {
            val tree = session.axSnapshot(ctx.toolTimeoutMs)
            val (text, refs) = compactAxTree(tree)
            session.storeSnapshotRefs(refs)
            FreshSnapshot(text)
        }.getOrNull()

    /**
     * Append the fresh page snapshot to an action's result JSON builder, so
     * the model sees the new state without another tool call.
     */
    private fun JsonObjectBuilder.attachSnapshot(snap: FreshSnapshot?) {
        if (snap != null) {
            put("snapshot", snap.text)
            put("snapshot_truncated", snap.text.length >= SNAPSHOT_MAX_CHARS)
        }
    }

    private suspend fun click(session: BrowserSession, arguments: String, ctx: GenerationContext): ActionOutcome {
        val ref = argStr(args(arguments), "ref")
            ?: return ActionOutcome("click", errorJson("browser_click", "no_ref", ""))
        if (!session.ensureConnected()) {
            return ActionOutcome("click", notConnected("browser_click"))
        }
        val binding = session.resolveRef(ref)
            ?: return ActionOutcome("click", errorJson("browser_click", "stale_ref", "Reference $ref is not from the latest snapshot. Take a new snapshot."))
        val center = elementCenter(binding, ctx.toolTimeoutMs)
            ?: return ActionOutcome("click", errorJson("browser_click", "not_visible", "Element $ref has no visible bounds."))
        session.mouseClick(center.first, center.second, ctx.toolTimeoutMs)
        val snap = captureFreshSnapshot(session, ctx)
        return ActionOutcome(
            "click",
            buildJsonObject {
                put("type", "browser")
                put("tool", "browser_click")
                put("ok", true)
                put("ref", ref)
                attachSnapshot(snap)
            }.toString(),
            mapOf("ref" to ref, "backend" to (session.currentBackendMode()?.persisted ?: "-")),
        )
    }

    private suspend fun fill(session: BrowserSession, arguments: String, ctx: GenerationContext): ActionOutcome {
        val a = args(arguments)
        val ref = argStr(a, "ref")
            ?: return ActionOutcome("fill", errorJson("browser_fill", "no_ref", ""))
        val text = argStr(a, "text")
        val credId = argStr(a, "cred_id")
        if (text == null && credId == null) {
            return ActionOutcome("fill", errorJson("browser_fill", "no_value", "Pass text or cred_id."))
        }
        // Resolve the secret BEFORE touching the browser; the value never
        // enters results, logs, or diagnostics.
        val secret: String? = if (credId != null) credentialResolver(credId) else null
        if (credId != null && secret.isNullOrEmpty()) {
            return ActionOutcome("fill", errorJson("browser_fill", "credential_not_found", "No credential for id."))
        }
        if (!session.ensureConnected()) {
            return ActionOutcome("fill", notConnected("browser_fill"))
        }
        val binding = session.resolveRef(ref)
            ?: return ActionOutcome("fill", errorJson("browser_fill", "stale_ref", "Reference $ref is not from the latest snapshot. Take a new snapshot."))
        val objectId = session.resolveNode(binding.backendNodeId, ctx.toolTimeoutMs)
            ?: return ActionOutcome("fill", errorJson("browser_fill", "not_found", "Element $ref could not be resolved."))
        session.focusObject(objectId, ctx.toolTimeoutMs)
        session.insertText(secret ?: text!!, ctx.toolTimeoutMs)
        val snap = captureFreshSnapshot(session, ctx)
        return ActionOutcome(
            "fill",
            buildJsonObject {
                put("type", "browser")
                put("tool", "browser_fill")
                put("ok", true)
                put("ref", ref)
                put("via", if (credId != null) "vault" else "text")
                attachSnapshot(snap)
            }.toString(),
            mapOf("ref" to ref, "backend" to (session.currentBackendMode()?.persisted ?: "-")),
        )
    }

    private suspend fun pressKey(session: BrowserSession, arguments: String, ctx: GenerationContext): ActionOutcome {
        val key = argStr(args(arguments), "key")
            ?: return ActionOutcome("key", errorJson("browser_key", "no_key", ""))
        if (!session.ensureConnected()) {
            return ActionOutcome("key", notConnected("browser_key"))
        }
        val mapped = NAMED_KEYS.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
        if (mapped != null) {
            session.dispatchKey("keyDown", mapped.first, mapped.second, ctx.toolTimeoutMs)
            session.dispatchKey("keyUp", mapped.first, mapped.second, ctx.toolTimeoutMs)
        } else if (key.length == 1) {
            // A single character is typed, not pressed.
            session.insertText(key, ctx.toolTimeoutMs)
        } else {
            return ActionOutcome("key", errorJson("browser_key", "unknown_key", "Unknown key: $key"))
        }
        val snap = captureFreshSnapshot(session, ctx)
        return ActionOutcome(
            "key",
            buildJsonObject {
                put("type", "browser")
                put("tool", "browser_key")
                put("ok", true)
                attachSnapshot(snap)
            }.toString(),
            mapOf("backend" to (session.currentBackendMode()?.persisted ?: "-")),
        )
    }

    private suspend fun scroll(session: BrowserSession, arguments: String, ctx: GenerationContext): ActionOutcome {
        val a = args(arguments)
        val direction = (argStr(a, "direction") ?: "down").lowercase()
        val pixels = argInt(a, "pixels", 400).coerceIn(1, 5000)
        if (!session.ensureConnected()) {
            return ActionOutcome("scroll", notConnected("browser_scroll"))
        }
        val (dx, dy) = when (direction) {
            "up" -> 0.0 to -pixels.toDouble()
            "left" -> -pixels.toDouble() to 0.0
            "right" -> pixels.toDouble() to 0.0
            else -> 0.0 to pixels.toDouble()
        }
        // Wheel at a fixed viewport point scrolls the page under it.
        session.mouseWheel(120.0, 200.0, dx, dy, ctx.toolTimeoutMs)
        val snap = captureFreshSnapshot(session, ctx)
        return ActionOutcome(
            "scroll",
            buildJsonObject {
                put("type", "browser")
                put("tool", "browser_scroll")
                put("ok", true)
                attachSnapshot(snap)
            }.toString(),
            mapOf("backend" to (session.currentBackendMode()?.persisted ?: "-")),
        )
    }

    private suspend fun screenshot(session: BrowserSession, ctx: GenerationContext): ActionOutcome {
        if (!session.ensureConnected()) {
            return ActionOutcome("screenshot", notConnected("browser_screenshot"))
        }
        val attachment = captureScreenshotAttachment(session, ctx)
        return ActionOutcome(
            "screenshot",
            buildJsonObject {
                put("type", "browser")
                put("tool", "browser_screenshot")
                put("ok", true)
                put("width", attachment.width ?: 0)
                put("height", attachment.height ?: 0)
            }.toString(),
            mapOf(
                "bytes" to attachment.sizeBytes.toString(),
                "backend" to (session.currentBackendMode()?.persisted ?: "-"),
            ),
        )
    }

    private suspend fun captureScreenshotResult(session: BrowserSession, ctx: GenerationContext): ToolExecutionResult {
        val attachment = captureScreenshotAttachment(session, ctx)
        return ToolExecutionResult(
            text = buildJsonObject {
                put("type", "browser")
                put("tool", "browser_screenshot")
                put("ok", true)
            }.toString(),
            images = listOf(attachment),
            transcribeImages = true,
        )
    }

    private suspend fun captureScreenshotAttachment(session: BrowserSession, ctx: GenerationContext): ToolImageAttachment {
        val bytes = session.captureScreenshot(ctx.toolTimeoutMs)
        return imageStore.persistBytes(bytes, "image/jpeg", "browser")
    }

    private suspend fun takeover(session: BrowserSession, arguments: String): ActionOutcome {
        val action = (argStr(args(arguments), "action") ?: "status").lowercase()
        // Takeover does not strictly need a live page, but connecting keeps the
        // state truthful about backend availability.
        session.ensureConnected()
        when (action) {
            "start" -> session.setTakeover(true)
            "stop" -> session.setTakeover(false)
        }
        val active = session.isTakeoverActive()
        return ActionOutcome(
            "takeover",
            buildJsonObject {
                put("type", "browser")
                put("tool", "browser_takeover")
                put("ok", true)
                put("takeover", if (active) "active" else "inactive")
            }.toString(),
            mapOf("backend" to (session.currentBackendMode()?.persisted ?: "-")),
        )
    }

    private suspend fun downloadStatus(session: BrowserSession): ActionOutcome {
        session.ensureConnected()
        val items = buildJsonArray {
            for (d in session.downloadStatus()) {
                add(buildJsonObject {
                    put("guid", d.guid)
                    put("state", d.state)
                    put("receivedBytes", d.receivedBytes)
                    put("totalBytes", d.totalBytes)
                })
            }
        }
        return ActionOutcome(
            "download_status",
            buildJsonObject {
                put("type", "browser")
                put("tool", "browser_download_status")
                put("ok", true)
                put("downloads", items)
            }.toString(),
            mapOf("backend" to (session.currentBackendMode()?.persisted ?: "-")),
        )
    }

    // ── helpers ──

    /** Center of the element's content quad; null when it has no visible bounds. */
    private suspend fun elementCenter(
        binding: BrowserSnapshotRef,
        timeoutMs: Long,
    ): Pair<Double, Double>? {
        val objectId = session.resolveNode(binding.backendNodeId, timeoutMs) ?: return null
        val model = session.boxModel(objectId, timeoutMs) ?: return null
        val content = (model["content"] as? JsonArray) ?: return null
        if (content.size < 8) return null
        val xs = listOf(0, 2, 4, 6).mapNotNull { (content[it] as? JsonPrimitive)?.doubleOrNull }
        val ys = listOf(1, 3, 5, 7).mapNotNull { (content[it] as? JsonPrimitive)?.doubleOrNull }
        if (xs.isEmpty() || ys.isEmpty()) return null
        val cx = xs.average()
        val cy = ys.average()
        if (xs.max() - xs.min() < 1 || ys.max() - ys.min() < 1) return null
        return cx to cy
    }

    /**
     * Flatten the AX tree into compact `@eN` lines. Typical pages land at
     * 2–8 KB. Refs bind to backend DOM node ids for click/fill.
     */
    private fun compactAxTree(tree: JsonObject): Pair<String, Map<String, BrowserSnapshotRef>> {
        val nodes = (tree["nodes"] as? JsonArray).orEmpty()
        val lines = ArrayList<String>(nodes.size.coerceAtMost(MAX_SNAPSHOT_NODES))
        val refs = LinkedHashMap<String, BrowserSnapshotRef>()
        var counter = 0
        var chars = 0
        for (element in nodes) {
            if (counter >= MAX_SNAPSHOT_NODES || chars >= SNAPSHOT_MAX_CHARS) break
            val node = element as? JsonObject ?: continue
            if ((node["ignored"] as? JsonPrimitive)?.booleanOrNull == true) continue
            val role = ((node["role"] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull
                .orEmpty().ifBlank { continue }
            if (role in SKIPPED_ROLES) continue
            val name = ((node["name"] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull
                .orEmpty().trim().replace(Regex("\\s+"), " ").take(120)
            val value = ((node["value"] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull
                .orEmpty().trim().replace(Regex("\\s+"), " ").take(80)
            if (name.isBlank() && value.isBlank() && role !in NAMELESS_ROLES) continue
            val backendNodeId = (node["backendDOMNodeId"] as? JsonPrimitive)?.longOrNull
            val label = buildString {
                if (backendNodeId != null) {
                    counter++
                    append("(@e").append(counter).append(") ")
                }
                append('[').append(role).append(']')
                if (name.isNotBlank()) append(" \"").append(name).append('"')
                if (value.isNotBlank()) append(" =\"").append(value).append('"')
            }
            lines.add(label)
            chars += label.length + 1
            if (backendNodeId != null) {
                refs["@e$counter"] = BrowserSnapshotRef("@e$counter", backendNodeId, role, name)
            }
        }
        return lines.joinToString("\n").take(SNAPSHOT_MAX_CHARS) to refs
    }



    private fun tool(
        name: String,
        description: String,
        properties: Map<String, ToolProperty>,
        required: List<String>,
    ): ToolDefinition = ToolDefinition(
        function = ToolFunction(
            name = name,
            description = description,
            parameters = ToolParameters(properties = properties, required = required),
        ),
    )

    private fun errorJson(tool: String, error: String, message: String, hint: String = ""): String =
        buildJsonObject {
            put("type", "browser")
            put("tool", tool)
            put("error", error)
            if (message.isNotBlank()) put("message", message)
            if (hint.isNotBlank()) put("hint", hint)
        }.toString()

    private fun errorCode(e: Exception): String = when (e) {
        is CdpException -> e.message?.substringBefore(':')?.take(40) ?: "cdp_error"
        else -> "error"
    }

    /**
     * isError follows the payload: failure JSON carries an "error" field,
     * success JSON carries "ok":true. Never rely on exception class names.
     */
    private fun isErrorJson(resultJson: String): Boolean = runCatching {
        val obj = json.parseToJsonElement(resultJson).jsonObject
        (obj["error"] as? JsonPrimitive)?.contentOrNull != null
    }.getOrDefault(false)

    /**
     * Full error triple for the agent: a short machine-readable code, the
     * complete failure detail (CDP method + message, never truncated to a
     * bare code), and an actionable hint. A bare `cdp_error(-32000)` with an
     * empty message is never emitted anymore.
     */
    private fun errorParts(e: Exception): Triple<String, String, String> {
        val detail = (e.message ?: e.javaClass.simpleName).take(DETAIL_MAX_CHARS)
        return Triple(errorCode(e), detail, hintFor(e))
    }

    /** Actionable guidance per failure signature, so the agent can recover on its own. */
    private fun hintFor(e: Exception): String {
        val msg = e.message ?: return ""
        return when {
            msg.startsWith("cdp_error(-32602)") ->
                "A browser command was rejected for invalid parameters during session setup. " +
                    "This is an engine bug, not a page problem — retry once; if it repeats, " +
                    "report the method name above."
            msg.startsWith("cdp_error(-32001)") || msg.startsWith("cdp_error(-32000)") ->
                "The browser tab's session died (renderer crash, tab closed, or tunnel runner " +
                    "restarted). AgentX already re-attached and retried once. Navigate to the page " +
                    "again, then retry the action."
            msg.startsWith("cdp_timeout:") ->
                "The browser did not answer in time — it may be busy loading or the device is " +
                    "slow. Wait a moment and retry; if it repeats, the page may be hanging the renderer."
            msg.startsWith("cdp_not_connected") || msg.startsWith("cdp_send_failed") ||
                msg.startsWith("cdp_disconnected") || msg.startsWith("cdp_closed") ->
                "No live CDP connection. Re-run the tool (it reconnects automatically); if it " +
                    "still fails, check the browser backend in Settings."
            else -> ""
        }
    }

    /** `not_connected` now carries WHY the backend failed to connect. */
    private fun notConnected(tool: String): String {
        val reason = session.lastConnectFailure()
        return errorJson(
            tool,
            "not_connected",
            reason ?: "The browser backend is not connected.",
            "Fix the cause above (e.g. enter the tunnel URL and token, or reinstall " +
                "Chromium in browser settings), then retry the tool.",
        )
    }

    private fun actionName(tool: String): String = tool.removePrefix("browser_")

    companion object {
        private const val TAG = "BrowserToolProvider"
        private const val MAX_SNAPSHOT_NODES = 200
        private const val SNAPSHOT_MAX_CHARS = 8000
        /** Cap on the failure detail surfaced to the agent (full method + message, not a bare code). */
        private const val DETAIL_MAX_CHARS = 300

        private val TOOL_NAMES = setOf(
            "browser_navigate", "browser_snapshot", "browser_click", "browser_fill",
            "browser_key", "browser_scroll", "browser_screenshot", "browser_takeover",
            "browser_download_status",
        )

        /** Structural roles that add noise without actionability. */
        private val SKIPPED_ROLES = setOf("none", "generic", "presentation", "StaticText")

        /** Roles worth listing even without an accessible name. */
        private val NAMELESS_ROLES = setOf("button", "link", "textbox", "checkbox", "radio", "image")

        /** (CDP key name, windowsVirtualKeyCode) for control keys. */
        private val NAMED_KEYS = mapOf(
            "Enter" to ("Enter" to 13),
            "Tab" to ("Tab" to 9),
            "Escape" to ("Escape" to 27),
            "Backspace" to ("Backspace" to 8),
            "Delete" to ("Delete" to 46),
            "ArrowLeft" to ("ArrowLeft" to 37),
            "ArrowUp" to ("ArrowUp" to 38),
            "ArrowRight" to ("ArrowRight" to 39),
            "ArrowDown" to ("ArrowDown" to 40),
        )
    }
}
