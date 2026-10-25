package com.newoether.agora.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2.4 WebView CDP backend unit tests — pure, JVM-safe seams only
 * (the socket bridge itself needs a device/emulator).
 */
class WebViewBrowserBackendTest {

    @Test
    fun `fromPersisted parses the webview mode`() {
        assertEquals(BrowserBackendMode.WEBVIEW, BrowserBackendMode.fromPersisted("webview"))
    }

    @Test
    fun `fromPersisted parses tunnel`() {
        assertEquals(BrowserBackendMode.TUNNEL, BrowserBackendMode.fromPersisted("tunnel"))
    }

    @Test
    fun `fromPersisted falls back to webview on unknown or removed modes`() {
        assertEquals(BrowserBackendMode.WEBVIEW, BrowserBackendMode.fromPersisted("nope"))
        assertEquals(BrowserBackendMode.WEBVIEW, BrowserBackendMode.fromPersisted(null))
        // Removed modes ("local", "geckoview") land on the working default.
        assertEquals(BrowserBackendMode.WEBVIEW, BrowserBackendMode.fromPersisted("local"))
        assertEquals(BrowserBackendMode.WEBVIEW, BrowserBackendMode.fromPersisted("geckoview"))
    }

    @Test
    fun `devtools socket name follows the webview_devtools_remote pid convention`() {
        assertEquals(
            "webview_devtools_remote_1234",
            WebViewBrowserBackend.devtoolsSocketNameForPid(1234),
        )
    }

    @Test
    fun `webview target ws url is built against the bridge port`() {
        assertEquals(
            "ws://127.0.0.1:45678/devtools/page/ABCDEF123456",
            BrowserSession.webViewTargetWsUrl(45678, "ABCDEF123456"),
        )
    }

    @Test
    fun `webview target ws url never leaks the abstract socket name`() {
        val url = BrowserSession.webViewTargetWsUrl(1111, "T1")
        assert(!url.contains("webview_devtools_remote")) { url }
    }

    @Test
    fun `webview target ws url is device-local`() {
        val url = BrowserSession.webViewTargetWsUrl(1111, "T1")
        assert(url.startsWith("ws://127.0.0.1:")) { url }
    }
}
