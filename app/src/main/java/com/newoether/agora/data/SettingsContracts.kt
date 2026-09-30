package com.newoether.agora.data

import androidx.datastore.preferences.core.Preferences
import com.newoether.agora.util.Constants
import com.newoether.agora.util.DebugLog
import com.newoether.agora.util.SecretCrypto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class ApiKeyEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val key: String,
    val provider: String = Constants.PROVIDER_GOOGLE
)

@Serializable
data class ShellDeviceConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val type: String = "conch",          // "conch" | "ssh"
    // Conch fields (type=conch)
    val serverUrl: String = "",
    val apiKey: String = "",
    val conchPublicKey: String = "",
    // SSH fields (type=ssh)
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "root",
    val sshPassword: String = "",
    // Pinned SSH host key (base64 of the server public-key blob). Blank = not yet
    // pinned (trust-on-first-use); once set, connections must match or are rejected.
    val sshHostKey: String = ""
)

@Serializable
enum class McpTransportType {
    @SerialName("streamable_http")
    STREAMABLE_HTTP,

    @SerialName("sse")
    SSE,
}

@Serializable
data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val url: String = "",
    val transport: McpTransportType = McpTransportType.STREAMABLE_HTTP,
    val headers: Map<String, String> = emptyMap(),
    /** Raw MCP tool names disabled for this server. New tools stay enabled by default. */
    val disabledTools: Set<String> = emptySet(),
)

@Serializable
data class SystemPromptEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String = "",
    val systemItems: List<PromptTemplateItem> = emptyList(),
    val userItems: List<PromptTemplateItem> = emptyList(),
    val assistantItems: List<PromptTemplateItem> = emptyList(),
    // Legacy fields retained only so prompts saved before structured message templates deserialize.
    val userPrependItems: List<PromptTemplateItem> = emptyList(),
    val userPostpendItems: List<PromptTemplateItem> = emptyList(),
) {
    val resolvedSystemItems: List<PromptTemplateItem>
        get() = if (systemItems.isNotEmpty()) systemItems
        else if (content.isNotBlank()) listOf(PromptTemplateItem(type = PromptItemType.CUSTOM, value = content))
        else emptyList()

    val resolvedUserItems: List<PromptTemplateItem>
        get() = PredefinedVariables.normalizeMessageTemplate(
            if (userItems.isNotEmpty()) {
                userItems
            } else {
                userPrependItems + PredefinedVariables.promptItem() + userPostpendItems
            },
        )

    val resolvedAssistantItems: List<PromptTemplateItem>
        get() = PredefinedVariables.normalizeMessageTemplate(assistantItems)
}

internal val WEB_SEARCH_PROVIDERS = setOf(
    "duckduckgo", "brave", "kagi", "serper", "tavily", "searxng", "tinyfish", "fusion",
)

internal fun normalizeWebSearchProvider(provider: String?): String =
    provider?.trim()?.lowercase()?.takeIf(WEB_SEARCH_PROVIDERS::contains) ?: "duckduckgo"

/** Agent modes: "off" (chat as today), "plan" (read-only tools), "build" (all tools + artifacts). */
internal val AGENT_MODES = setOf("off", "plan", "build")

internal fun normalizeAgentMode(mode: String?): String =
    mode?.trim()?.lowercase()?.takeIf(AGENT_MODES::contains) ?: "off"

internal fun decodeAgentModels(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        Json.decodeFromString<List<String>>(raw).map { it.trim() }
            .filter { it.isNotBlank() }.distinct().take(5)
    } catch (_: Exception) {
        emptyList()
    }
}

/** Agent environment variables: name -> secret value. Encrypted at rest. */
internal val AGENT_ENV_NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
internal const val AGENT_ENV_MAX_VARS = 50
internal const val AGENT_ENV_MAX_VALUE_BYTES = 4096

internal fun isValidAgentEnvName(name: String): Boolean =
    name.length <= 64 && AGENT_ENV_NAME.matches(name)

internal fun decodeAgentEnv(raw: String?, json: Json): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return try {
        val decrypted = SecretCrypto.decrypt(raw)
        json.decodeFromString<Map<String, String>>(decrypted)
            .filterKeys { isValidAgentEnvName(it) }
    } catch (_: Exception) {
        emptyMap()
    }
}

internal fun encodeAgentEnv(env: Map<String, String>, json: Json): String {
    val cleaned = env.filterKeys { isValidAgentEnvName(it) }
        .mapValues { it.value.take(AGENT_ENV_MAX_VALUE_BYTES) }
        .toList().take(AGENT_ENV_MAX_VARS).toMap()
    return SecretCrypto.encrypt(json.encodeToString(cleaned))
}

internal fun decodeWebSearchApiKeys(preferences: Preferences, json: Json): Map<String, String> {
    val raw = SecretCrypto.decrypt(preferences[WEB_SEARCH_API_KEYS_JSON] ?: "{}")
    return try {
        json.decodeFromString<Map<String, String>>(raw)
    } catch (error: Exception) {
        DebugLog.e("SettingsManager", "Failed to decode webSearchApiKeys", error)
        emptyMap()
    }
}

internal fun decodeConversationSettings(
    preferences: Preferences,
    json: Json,
): Map<String, ConversationSettings> = try {
    json.decodeFromString(preferences[CONVERSATION_SETTINGS_JSON] ?: "{}")
} catch (_: Exception) {
    emptyMap()
}

internal fun decodeEncryptedShellDevices(preferences: Preferences, json: Json): List<ShellDeviceConfig> {
    val raw = SecretCrypto.decrypt(preferences[SHELL_DEVICES_JSON] ?: "[]")
    return runCatching { json.decodeFromString<List<ShellDeviceConfig>>(raw) }.getOrDefault(emptyList())
}

@Serializable
data class ConversationSettings(
    /** Provider-visible conversation token budget. Values <=100 are legacy message windows. */
    val contextWindow: Int? = null,
    val temperature: Float? = null,
    val maxTokens: Int? = null,
    val topP: Float? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val codeExecutionEnabled: Boolean? = null,
    val googleSearchEnabled: Boolean? = null,
    val openAiWebSearchEnabled: Boolean? = null,
    val thinkingEnabled: Boolean? = null,
    val thinkingLevel: String? = null,
    val thinkingBudgetEnabled: Boolean? = null,
    val thinkingBudgetTokens: Int? = null,
    val openAiServiceTierEnabled: Boolean? = null,
    val openAiServiceTier: String? = null,
    val webSearchEnabled: Boolean? = null,
    val shellEnabled: Boolean? = null,
    val lowContextModeEnabled: Boolean? = null,
) {
    fun isAllNull() = contextWindow == null && temperature == null && maxTokens == null && topP == null
        && frequencyPenalty == null && presencePenalty == null
        && codeExecutionEnabled == null && googleSearchEnabled == null
        && openAiWebSearchEnabled == null && thinkingEnabled == null
        && thinkingLevel == null && thinkingBudgetEnabled == null && thinkingBudgetTokens == null
        && openAiServiceTierEnabled == null && openAiServiceTier == null
        && webSearchEnabled == null && shellEnabled == null
        && lowContextModeEnabled == null
}
