package com.newoether.agora.browser

/**
 * Browser automation transport backend (§1.3.0 of the v2.3.0 architecture plan).
 *
 * Everything downstream (CDP client, tools, audit) is identical — only the
 * transport endpoint changes:
 * - [LOCAL]: sandbox Chromium on-device (default).
 * - [TUNNEL]: user-supplied CDP-over-HTTPS endpoint + client token. The app
 *   ships no default tunnel URL; without one, tunnel mode falls back to [LOCAL].
 * - [WEBVIEW]: Android System WebView on-device via its
 *   `@webview_devtools_remote_<pid>` abstract socket, bridged to 127.0.0.1 by
 *   [WebViewBrowserBackend]. No sandbox download, no extra process.
 */
enum class BrowserBackendMode(val persisted: String) {
    LOCAL("local"),
    TUNNEL("tunnel"),
    WEBVIEW("webview");

    companion object {
        fun fromPersisted(raw: String?): BrowserBackendMode =
            entries.firstOrNull { it.persisted == raw } ?: LOCAL
    }
}
