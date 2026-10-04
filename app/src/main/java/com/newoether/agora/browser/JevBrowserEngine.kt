package com.newoether.agora.browser

import com.newoether.agora.api.typesafe.JevDecisions
import com.newoether.agora.api.typesafe.TypeSafeClient
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Jev-driven fast browser automation (jev-ultrafast style).
 *
 * The manual browser_* tools cost one full main-model round-trip per action
 * (10–140s each, context exploding to 290k tokens). This engine collapses the
 * loop: snapshot → ONE Jev Choice call (~1–2s) → CDP execute → repeat, all
 * inside a single tool call. The agent calls `browser_run_task` once and gets
 * back a summary instead of babysitting every click.
 *
 * Fail-open (kojev rule): returns [TaskResult] with status `engine_unavailable`
 * when Jev is not configured, and `engine_error` when Jev fails mid-task.
 * The agent must then fall back to the manual browser_* tools (main model).
 * Jev never breaks a user-visible flow.
 *
 * Decision provider: [GenerationContext.decisionProvider] selects "jev"
 * (TypeSafe) or "drex" (Drex by Nace AI — wire-compatible). Key/base URL/
 * model come from the Jev settings; empty key = engine unavailable.
 */
object JevBrowserEngine {
    private const val TAG = "JevBrowser"
    private val json = Json { ignoreUnknownKeys = true }

    /** Max actions per task — bounds cost when the page fights back. */
    const val DEFAULT_MAX_STEPS = 20
    /** Min Jev confidence to act; below this the engine stops as uncertain. */
    private const val MIN_CONFIDENCE = 0.35
    /** Chars of element table sent to Jev per step (Drex allows ~4x Jev). */
    private const val ELEMENT_TABLE_CHARS = 4000

    data class TaskResult(
        /** done | blocked | max_steps | uncertain | need_text | engine_unavailable | engine_error */
        val status: String,
        /** Human-readable action log, one line per step. */
        val steps: List<String>,
        val finalUrl: String,
        /** Compact final snapshot for the agent to verify outcome. */
        val finalSnapshot: String,
        /** Secret-free reason when status is not done. */
        val detail: String = "",
    )

    /**
     * Run [goal] against [session]. Returns null only when the engine cannot
     * even start (not configured) — the caller maps that to
     * `engine_unavailable` without a Jev call.
     */
    suspend fun runTask(
        goal: String,
        typeText: String,
        maxSteps: Int,
        session: BrowserSession,
        ctx: GenerationContext,
    ): TaskResult {
        if (!ctx.jevEnabled || !JevDecisions.isConfigured(ctx.typeSafeApiKey)) {
            return TaskResult(
                status = "engine_unavailable",
                steps = emptyList(),
                finalUrl = "",
                finalSnapshot = "",
                detail = "Jev engine not configured — add a key in Settings → Jev (or select Drex) to enable fast browser automation.",
            )
        }
        if (!session.ensureConnected()) {
            return TaskResult(
                status = "engine_error", steps = emptyList(), finalUrl = "",
                finalSnapshot = "", detail = "browser not connected",
            )
        }
        val steps = ArrayList<String>()
        val max = maxSteps.coerceIn(1, 40)
        var lastStatus = "max_steps"
        var lastDetail = "step budget exhausted"
        repeat(max) { index ->
            val snap = readElements(session, ctx) ?: run {
                lastStatus = "engine_error"; lastDetail = "could not read page"
                return@repeat
            }
            val decision = decideNextAction(goal, typeText, snap, session, ctx)
                ?: run {
                    lastStatus = "engine_error"
                    lastDetail = "decision endpoint failed"
                    return@repeat
                }
            when (decision.action) {
                "done" -> {
                    steps.add("done (${decision.label})")
                    lastStatus = "done"; lastDetail = ""
                    return@repeat
                }
                "blocked" -> {
                    steps.add("blocked (${decision.label})")
                    lastStatus = "blocked"; lastDetail = decision.label
                    return@repeat
                }
                "need_text" -> {
                    steps.add("need_text (${decision.label})")
                    lastStatus = "need_text"
                    lastDetail = "a text field needs input but no type_text was provided"
                    return@repeat
                }
                "uncertain" -> {
                    steps.add("uncertain (${decision.label})")
                    lastStatus = "uncertain"; lastDetail = decision.label
                    return@repeat
                }
                else -> {
                    val outcome = executeAction(decision, typeText, snap, session, ctx)
                    steps.add(outcome)
                    // Small settle: jev-ultrafast waits ≤200ms after typing for
                    // suggestions, ≤50ms otherwise. CDP round-trips dominate.
                    kotlinx.coroutines.delay(150)
                }
            }
            if (index == max - 1) { /* falls through with max_steps */ }
        }
        val finalSnap = readElements(session, ctx)
        return TaskResult(
            status = lastStatus,
            steps = steps,
            finalUrl = runCatching { session.currentUrl() }.getOrNull().orEmpty(),
            finalSnapshot = finalSnap?.tableText?.take(3000).orEmpty(),
            detail = lastDetail,
        )
    }

    // ── element table ──

    private data class PageElements(
        val tableText: String,
        /** ref -> (backendNodeId, role, name, clickable, textInput) */
        val refs: Map<String, ElementInfo>,
    )

    private data class ElementInfo(
        val backendNodeId: Long,
        val role: String,
        val name: String,
    )

    private val CLICKABLE_ROLES = setOf(
        "button", "link", "checkbox", "radio", "menuitem",
        "tab", "switch", "menuitemcheckbox", "menuitemradio",
    )
    private val TEXT_ROLES = setOf(
        "textbox", "searchbox", "combobox", "spinbutton",
    )

    private suspend fun readElements(
        session: BrowserSession,
        ctx: GenerationContext,
    ): PageElements? = runCatching {
        val tree = session.axSnapshot(ctx.toolTimeoutMs)
        val nodes = (tree["nodes"] as? JsonArray).orEmpty()
        val lines = ArrayList<String>()
        val refs = LinkedHashMap<String, ElementInfo>()
        var counter = 0
        var chars = 0
        for (element in nodes) {
            if (chars >= ELEMENT_TABLE_CHARS) break
            val node = element as? JsonObject ?: continue
            if ((node["ignored"] as? JsonPrimitive)?.booleanOrNull == true) continue
            val role = ((node["role"] as? JsonObject)?.get("value")
                as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { continue }
            val name = ((node["name"] as? JsonObject)?.get("value")
                as? JsonPrimitive)?.contentOrNull.orEmpty()
                .trim().replace(Regex("\\s+"), " ").take(100)
            val value = ((node["value"] as? JsonObject)?.get("value")
                as? JsonPrimitive)?.contentOrNull.orEmpty()
                .trim().replace(Regex("\\s+"), " ").take(60)
            if (name.isBlank() && value.isBlank()) continue
            val backendNodeId =
                (node["backendDOMNodeId"] as? JsonPrimitive)?.longOrNull ?: continue
            counter++
            val ref = "@e$counter"
            val kind = when (role.lowercase()) {
                in CLICKABLE_ROLES -> "tap"
                in TEXT_ROLES -> "type"
                else -> "see"
            }
            val line = "[$counter] $kind [$role] \"$name\"${if (value.isNotBlank()) " =\"$value\"" else ""}"
            lines.add(line)
            chars += line.length + 1
            refs[ref] = ElementInfo(backendNodeId, role, name)
        }
        session.storeSnapshotRefs(refs.mapValues { (ref, info) ->
            BrowserSnapshotRef(ref, info.backendNodeId, info.role, info.name)
        })
        PageElements(lines.joinToString("\n"), refs)
    }.getOrNull()

    // ── Jev decision ──

    private data class NextAction(
        /** click | fill | scroll_down | scroll_up | wait | done | blocked | need_text | uncertain */
        val action: String,
        val ref: String?,
        val label: String,
    )

    private suspend fun decideNextAction(
        goal: String,
        typeText: String,
        snap: PageElements,
        session: BrowserSession,
        ctx: GenerationContext,
    ): NextAction? {
        val criteria = LinkedHashMap<String, String?>()
        for ((ref, info) in snap.refs) {
            val n = ref.removePrefix("@e")
            when (info.role.lowercase()) {
                in CLICKABLE_ROLES ->
                    criteria["click_$n"] = "Click (${ref}) [${info.role}] \"${info.name}\""
                in TEXT_ROLES ->
                    criteria["fill_$n"] = "Type into (${ref}) [${info.role}] \"${info.name}\""
            }
        }
        criteria["scroll_down"] = "Scroll the page down"
        criteria["scroll_up"] = "Scroll the page up"
        criteria["wait"] = "Wait briefly for the page to load or settle"
        criteria["done"] = "The goal is achieved — stop"
        criteria["blocked"] = "Cannot make progress (login wall, captcha, error) — stop"
        if (criteria.size <= 5) {
            // Only the fixed actions: nothing actionable on the page.
            return NextAction("blocked", null, "no actionable elements on page")
        }
        val state = buildJsonObject {
            put("goal", goal)
            put("url", runCatching { session.currentUrl() }.getOrNull().orEmpty())
            put("has_type_text", typeText.isNotBlank())
            put("elements", snap.tableText)
        }
        val answer = try {
            val decision = TypeSafeClient.decide(
                apiKey = ctx.typeSafeApiKey,
                baseUrl = ctx.typeSafeBaseUrl,
                model = ctx.jevModel.ifBlank { TypeSafeClient.DEFAULT_MODEL },
                state = state,
                questions = mapOf(
                    "next_action" to TypeSafeClient.ChoiceQuestion(
                        key = "next_action",
                        instructions = "Given the goal, pick the single best next browser action. " +
                            "Prefer clicking the most relevant link/button. " +
                            "Choose done only when the goal is visibly achieved. " +
                            "Choose blocked for login walls, captchas, or errors.",
                        criteria = criteria,
                    ),
                ),
                timeoutMs = ctx.decisionTimeoutMs,
            )
            decision.answers["next_action"] as? TypeSafeClient.JevAnswer.Choice
        } catch (e: Exception) {
            DebugLog.d(TAG, "Jev decide failed: ${e.javaClass.simpleName}")
            return null
        } ?: return null
        if (answer.confidence < MIN_CONFIDENCE) {
            return NextAction("uncertain", null, "low confidence ${answer.confidence}")
        }
        val value = answer.value
        DebugLog.d(TAG, "Jev next_action=$value conf=${answer.confidence}")
        return when {
            value == "done" -> NextAction("done", null, "goal achieved")
            value == "blocked" -> NextAction("blocked", null, "page blocked progress")
            value == "scroll_down" -> NextAction("scroll_down", null, "scroll down")
            value == "scroll_up" -> NextAction("scroll_up", null, "scroll up")
            value == "wait" -> NextAction("wait", null, "wait for settle")
            value.startsWith("click_") -> {
                val ref = "@e" + value.removePrefix("click_")
                if (snap.refs.containsKey(ref)) NextAction("click", ref, "click $ref")
                else NextAction("uncertain", null, "stale ref $ref")
            }
            value.startsWith("fill_") -> {
                val ref = "@e" + value.removePrefix("fill_")
                if (!snap.refs.containsKey(ref)) return NextAction("uncertain", null, "stale ref $ref")
                if (typeText.isBlank()) NextAction("need_text", ref, "need text for $ref")
                else NextAction("fill", ref, "fill $ref")
            }
            else -> NextAction("uncertain", null, "unknown choice $value")
        }
    }

    // ── execution ──

    private suspend fun executeAction(
        decision: NextAction,
        typeText: String,
        snap: PageElements,
        session: BrowserSession,
        ctx: GenerationContext,
    ): String {
        val timeout = ctx.toolTimeoutMs
        return runCatching {
            when (decision.action) {
                "click" -> {
                    val info = snap.refs[decision.ref] ?: return "click: stale ref"
                    val center = elementCenter(session, info.backendNodeId, timeout)
                        ?: return "click ${decision.ref}: not visible"
                    session.setActionCursor(center.first, center.second, timeout)
                    session.mouseClick(center.first, center.second, timeout)
                    "clicked ${decision.ref} \"${info.name}\""
                }
                "fill" -> {
                    val info = snap.refs[decision.ref] ?: return "fill: stale ref"
                    val objectId = session.resolveNode(info.backendNodeId, timeout)
                        ?: return "fill ${decision.ref}: not found"
                    val center = elementCenter(session, info.backendNodeId, timeout)
                    if (center != null) session.setActionCursor(center.first, center.second, timeout)
                    session.focusObject(objectId, timeout)
                    session.insertText(typeText, timeout)
                    "typed into ${decision.ref} \"${info.name}\""
                }
                "scroll_down" -> {
                    session.mouseWheel(120.0, 200.0, 0.0, 400.0, timeout)
                    "scrolled down"
                }
                "scroll_up" -> {
                    session.mouseWheel(120.0, 200.0, 0.0, -400.0, timeout)
                    "scrolled up"
                }
                "wait" -> {
                    kotlinx.coroutines.delay(1200)
                    "waited"
                }
                else -> "noop ${decision.action}"
            }
        }.getOrElse { e ->
            "error ${decision.action}: ${e.javaClass.simpleName}"
        }
    }

    private suspend fun elementCenter(
        session: BrowserSession,
        backendNodeId: Long,
        timeoutMs: Long,
    ): Pair<Double, Double>? {
        val objectId = session.resolveNode(backendNodeId, timeoutMs) ?: return null
        val model = session.boxModel(objectId, timeoutMs) ?: return null
        val content = (model["content"] as? JsonArray) ?: return null
        if (content.size < 8) return null
        val xs = listOf(0, 2, 4, 6).mapNotNull { (content[it] as? JsonPrimitive)?.doubleOrNull }
        val ys = listOf(1, 3, 5, 7).mapNotNull { (content[it] as? JsonPrimitive)?.doubleOrNull }
        if (xs.isEmpty() || ys.isEmpty()) return null
        if (xs.max() - xs.min() < 1 || ys.max() - ys.min() < 1) return null
        return xs.average() to ys.average()
    }
}
