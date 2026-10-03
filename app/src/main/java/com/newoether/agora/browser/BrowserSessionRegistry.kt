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
     * The session for a conversation. `null` (agent work outside a chat)
     * maps to the shared "default" session.
     */
    fun get(conversationId: String?): BrowserSession {
        val key = conversationId ?: WebViewBrowserBackend.DEFAULT_SESSION_KEY
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

    /**
     * The session for a conversation, or null when none exists yet (B6).
     * Unlike [get], this does NOT create a session — use it on read paths
     * (watch controller) so merely viewing a chat never instantiates one.
     */
    fun peek(conversationId: String?): BrowserSession? {
        val key = conversationId ?: WebViewBrowserBackend.DEFAULT_SESSION_KEY
        return sessions[key]
    }

    /** All live session keys (for diagnostics). */
    fun keys(): Set<String> = sessions.keys.toSet()

    /**
     * Stop and drop a chat's browser session. The page is REALLY closed:
     * the WebView is destroyed (B1), not just detached from the map.
     */
    suspend fun close(conversationId: String?) {
        val key = conversationId ?: WebViewBrowserBackend.DEFAULT_SESSION_KEY
        val session = sessions.remove(key) ?: return
        runCatching { session.close() }
        // Destroy the WebView so Stop actually frees the page (was leaking).
        runCatching { webViewBackend.destroySession(key) }
        DebugLog.d(TAG, "closed browser session for key=$key")
    }

    private fun newCdpClient(): CdpClient = CdpClient(
        // ~20s WebSocket ping: an idle CDP socket is the normal state,
        // never a dead one — keep it alive, don't cut it.
        HttpClient.client.newBuilder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build(),
        scope,
    )

    companion object {
        private const val TAG = "BrowserSessionRegistry"
    }
}
