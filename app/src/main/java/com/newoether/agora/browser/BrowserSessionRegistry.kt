package com.newoether.agora.browser

import com.newoether.agora.api.HttpClient
import com.newoether.agora.browser.cdp.CdpClient
import com.newoether.agora.util.DebugLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient

/**
 * One [BrowserSession] per chat.
 *
 * The agent's browser tools resolve their session from
 * `GenerationContext.conversationId` through this registry, so opening a
 * browser in chat A and another in chat B gives two fully independent
 * browsers — separate pages, separate CDP connections, no overlap:
 *
 * - TUNNEL: each session attaches to its own cloud page target
 *   (`Target.createTarget`), multiplexed over one relay connection each.
 * - WEBVIEW: each session owns its own System WebView instance
 *   ([WebViewBrowserBackend.ensureStarted] keyed by session).
 *
 * Sessions are created lazily on first use and live until [close] (the Stop
 * button). Closing the watch panel never closes the session — only Stop does.
 */
class BrowserSessionRegistry(
    private val prefs: BrowserPreferenceStore,
    private val webViewBackend: WebViewBrowserBackend,
    private val healthHttp: OkHttpClient,
    private val scope: CoroutineScope,
    /** Wired by AppContainer: session-level audit events join the trail. */
    var onSessionCreated: ((BrowserSession) -> Unit)? = null,
) {
    private val sessions = ConcurrentHashMap<String, BrowserSession>()
    /**
     * Active tab per conversation. Tab ids are short strings ("1", "2", ...);
     * the default tab is "1". Tools resolve the session for the active tab.
     */
    private val activeTabs = ConcurrentHashMap<String, String>()

    companion object {
        const val DEFAULT_TAB_ID = "1"
        private const val TAG = "BrowserSessionRegistry"
    }
}

    private fun tabKey(conversationId: String?, tabId: String): String {
        val conv = conversationId ?: WebViewBrowserBackend.DEFAULT_SESSION_KEY
        return "$conv#tab$tabId"
    }

    private fun convKey(conversationId: String?): String =
        conversationId ?: WebViewBrowserBackend.DEFAULT_SESSION_KEY

    /**
     * The session for a conversation's ACTIVE tab. `null` (agent work outside
     * a chat) maps to the shared "default" session.
     */
    fun get(conversationId: String?): BrowserSession {
        val tabId = activeTabs[convKey(conversationId)] ?: DEFAULT_TAB_ID
        return getTab(conversationId, tabId)
    }

    /** The session for a specific tab, creating it if needed. */
    fun getTab(conversationId: String?, tabId: String): BrowserSession {
        val key = tabKey(conversationId, tabId)
        return sessions.getOrPut(key) {
            BrowserSession(
                prefs = prefs,
                webViewBackend = webViewBackend,
                cdp = newCdpClient(),
                healthHttp = healthHttp,
                scope = scope,
                sessionKey = key,
            ).also { session ->
                DebugLog.d(TAG, "created browser session for key=$key")
                runCatching { onSessionCreated?.invoke(session) }
            }
        }
    }

    /** Active tab id for a conversation ("1" when never switched). */
    fun activeTab(conversationId: String?): String =
        activeTabs[convKey(conversationId)] ?: DEFAULT_TAB_ID

    /** All tab ids with a live session for a conversation, sorted. */
    fun listTabs(conversationId: String?): List<String> {
        val prefix = "${convKey(conversationId)}#tab"
        return sessions.keys.mapNotNull { k ->
            k.removePrefix(prefix).takeIf { k.startsWith(prefix) && it.isNotBlank() }
        }.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
    }

    /**
     * Switch the active tab (creating it lazily). Returns the tab id.
     * Tab ids are assigned sequentially: new tabs get max+1.
     */
    fun newTab(conversationId: String?): String {
        val existing = listTabs(conversationId)
        val next = ((existing.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
        getTab(conversationId, next)
        activeTabs[convKey(conversationId)] = next
        return next
    }

    /** Switch to an existing tab. False when the tab does not exist. */
    fun switchTab(conversationId: String?, tabId: String): Boolean {
        if (tabId !in listTabs(conversationId)) return false
        activeTabs[convKey(conversationId)] = tabId
        return true
    }

    /** Close one tab. The default tab cannot be closed while others exist. */
    suspend fun closeTab(conversationId: String?, tabId: String): Boolean {
        val key = tabKey(conversationId, tabId)
        val session = sessions.remove(key) ?: return false
        runCatching { session.close() }
        runCatching { webViewBackend.destroySession(key) }
        if (activeTab(conversationId) == tabId) {
            val remaining = listTabs(conversationId)
            activeTabs[convKey(conversationId)] = remaining.firstOrNull() ?: DEFAULT_TAB_ID
            if (remaining.isEmpty()) activeTabs.remove(convKey(conversationId))
        }
        DebugLog.d(TAG, "closed browser tab $tabId for ${convKey(conversationId)}")
        return true
    }

    /**
     * The session for a conversation's active tab, or null when none exists
     * yet (B6). Unlike [get], this does NOT create a session — use it on read
     * paths (watch controller) so merely viewing a chat never instantiates one.
     */
    fun peek(conversationId: String?): BrowserSession? {
        val tabId = activeTabs[convKey(conversationId)] ?: DEFAULT_TAB_ID
        return sessions[tabKey(conversationId, tabId)]
    }

    /** All live session keys (for diagnostics). */
    fun keys(): Set<String> = sessions.keys.toSet()

    /**
     * Stop and drop a chat's browser sessions (ALL tabs). Pages are REALLY
     * closed: WebViews are destroyed (B1), not just detached from the map.
     */
    suspend fun close(conversationId: String?) {
        val conv = convKey(conversationId)
        val prefix = "$conv#tab"
        val keys = sessions.keys.filter { it.startsWith(prefix) }
        for (key in keys) {
            val session = sessions.remove(key) ?: continue
            runCatching { session.close() }
            runCatching { webViewBackend.destroySession(key) }
        }
        activeTabs.remove(conv)
        DebugLog.d(TAG, "closed ${keys.size} browser tab(s) for key=$conv")
    }

    private fun newCdpClient(): CdpClient = CdpClient(
        // ~20s WebSocket ping: an idle CDP socket is the normal state,
        // never a dead one — keep it alive, don't cut it.
        HttpClient.client.newBuilder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build(),
        scope,
    )
}
