package com.newoether.agora.browser

import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Fast browser task tools: `browser_run_task` (Jev/Drex engine) and
 * `browser_tab` (multi-tab management).
 *
 * Split from [BrowserToolProvider] to keep that file under the 800-line
 * handwritten limit. Shares the provider's JSON helpers via [Shared].
 */
object BrowserTaskTools {
    private val json = Json { ignoreUnknownKeys = true }

    /** JSON helpers shared with BrowserToolProvider (same shapes). */
    object Shared {
        fun args(arguments: String): JsonObject =
            try {
                json.parseToJsonElement(arguments.ifBlank { "{}" }) as JsonObject
            } catch (_: Exception) {
                buildJsonObject {}
            }

        fun argStr(a: JsonObject, name: String): String? =
            (a[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        fun argInt(a: JsonObject, name: String, default: Int): Int =
            (a[name] as? JsonPrimitive)?.intOrNull ?: default

        fun errorJson(tool: String, error: String, message: String, hint: String = ""): String =
            buildJsonObject {
                put("type", "browser")
                put("tool", tool)
                put("error", error)
                if (message.isNotBlank()) put("message", message)
                if (hint.isNotBlank()) put("hint", hint)
            }.toString()
    }

    data class Outcome(
        val action: String,
        val json: String,
        val extra: Map<String, String> = emptyMap(),
    )

    /**
     * Fast task runner: hands [goal] to [JevBrowserEngine], which drives the
     * browser with one Jev/Drex decision per action instead of one main-model
     * round-trip per click. Fail-open: engine_unavailable/engine_error means
     * the agent should fall back to the manual browser_* tools.
     */
    suspend fun runTask(
        registry: BrowserSessionRegistry,
        arguments: String,
        ctx: GenerationContext,
    ): Outcome {
        val a = Shared.args(arguments)
        val goal = Shared.argStr(a, "goal")
            ?: return Outcome("run_task", Shared.errorJson("browser_run_task", "no_goal", ""))
        val typeText = Shared.argStr(a, "type_text").orEmpty()
        val maxSteps = Shared.argInt(a, "max_steps", JevBrowserEngine.DEFAULT_MAX_STEPS)
        val session = registry.get(ctx.conversationId)
        session.diagnosticContext = ctx
        val result = JevBrowserEngine.runTask(goal, typeText, maxSteps, session, ctx)
        val isError = result.status == "engine_error"
        return Outcome(
            "run_task",
            buildJsonObject {
                put("type", "browser")
                put("tool", "browser_run_task")
                put("ok", !isError && result.status != "engine_unavailable")
                put("status", result.status)
                put("steps", buildJsonArray { result.steps.forEach { add(it) } })
                put("final_url", result.finalUrl)
                put("final_snapshot", result.finalSnapshot)
                if (result.detail.isNotBlank()) put("detail", result.detail)
            }.toString(),
            mapOf(
                "engine_status" to result.status,
                "steps" to result.steps.size.toString(),
                "backend" to (session.currentBackendMode()?.persisted ?: "-"),
            ),
        )
    }

    /**
     * Tab management: list / new / switch / close. Tabs are independent
     * browser sessions within the chat; tools always act on the active tab.
     */
    suspend fun manageTab(
        registry: BrowserSessionRegistry,
        arguments: String,
        ctx: GenerationContext,
    ): Outcome {
        val a = Shared.args(arguments)
        val action = (Shared.argStr(a, "action") ?: "list").lowercase()
        val convId = ctx.conversationId
        return when (action) {
            "list" -> {
                val tabs = registry.listTabs(convId)
                val active = registry.activeTab(convId)
                Outcome(
                    "tab",
                    buildJsonObject {
                        put("type", "browser")
                        put("tool", "browser_tab")
                        put("ok", true)
                        put("active", active)
                        put("tabs", buildJsonArray { tabs.forEach { add(it) } })
                    }.toString(),
                    mapOf("active" to active, "count" to tabs.size.toString()),
                )
            }
            "new" -> {
                val tabId = registry.newTab(convId)
                Outcome(
                    "tab",
                    buildJsonObject {
                        put("type", "browser")
                        put("tool", "browser_tab")
                        put("ok", true)
                        put("active", tabId)
                        put("detail", "new tab $tabId opened and active")
                    }.toString(),
                    mapOf("active" to tabId),
                )
            }
            "switch" -> {
                val tabId = Shared.argStr(a, "tab")
                    ?: return Outcome("tab", Shared.errorJson("browser_tab", "no_tab", "Pass tab id."))
                if (!registry.switchTab(convId, tabId)) {
                    return Outcome("tab", Shared.errorJson("browser_tab", "no_such_tab", "Tab $tabId does not exist."))
                }
                Outcome(
                    "tab",
                    buildJsonObject {
                        put("type", "browser")
                        put("tool", "browser_tab")
                        put("ok", true)
                        put("active", tabId)
                    }.toString(),
                    mapOf("active" to tabId),
                )
            }
            "close" -> {
                val tabId = Shared.argStr(a, "tab")
                    ?: return Outcome("tab", Shared.errorJson("browser_tab", "no_tab", "Pass tab id."))
                if (!registry.closeTab(convId, tabId)) {
                    return Outcome("tab", Shared.errorJson("browser_tab", "no_such_tab", "Tab $tabId does not exist."))
                }
                Outcome(
                    "tab",
                    buildJsonObject {
                        put("type", "browser")
                        put("tool", "browser_tab")
                        put("ok", true)
                        put("active", registry.activeTab(convId))
                        put("detail", "tab $tabId closed")
                    }.toString(),
                    emptyMap(),
                )
            }
            else -> Outcome("tab", Shared.errorJson("browser_tab", "bad_action", "Action must be list, new, switch, or close."))
        }
    }
}
