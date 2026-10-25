package com.newoether.agora.browser.cdp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session-invalid matcher drives CdpClient's self-healing: a dead page
 * session (stale id, closed target, dead context) must be recognized so the
 * client re-attaches and retries instead of failing every tool call forever
 * with a bare `cdp_error(-32000)`.
 */
class CdpSessionInvalidMatcherTest {

    @Test
    fun sessionNotFound_isInvalid() {
        assertTrue(isSessionInvalidError("cdp_error(-32001):Page.captureScreenshot: Session with given id not found."))
    }

    @Test
    fun targetClosed_isInvalid() {
        assertTrue(isSessionInvalidError("cdp_error(-32000):Page.navigate: No target with given id"))
    }

    @Test
    fun contextDestroyed_isInvalid() {
        assertTrue(isSessionInvalidError("cdp_error(-32000):DOM.resolveNode: Cannot find context with specified id"))
    }

    @Test
    fun rendererCrashed_isInvalid() {
        assertTrue(isSessionInvalidError("cdp_error(-32000):Input.dispatchMouseEvent: Target crashed"))
    }

    @Test
    fun disconnected_isInvalid() {
        assertTrue(isSessionInvalidError("cdp_disconnected:closed(code=1006)"))
    }

    @Test
    fun null_isNotInvalid() {
        assertFalse(isSessionInvalidError(null))
    }

    @Test
    fun invalidParams_isNotInvalid() {
        // -32602 Invalid params is a caller bug, not a dead session — no retry.
        assertFalse(isSessionInvalidError("cdp_error(-32602):Page.navigate: Invalid parameters"))
    }

    @Test
    fun timeout_isNotInvalid() {
        assertFalse(isSessionInvalidError("cdp_timeout:Page.captureScreenshot"))
    }

    @Test
    fun notConnected_isNotInvalid() {
        assertFalse(isSessionInvalidError("cdp_not_connected"))
    }

    @Test
    fun generic32000_isNotInvalid() {
        // A -32000 WITHOUT a session/target/context marker is not healed.
        assertFalse(isSessionInvalidError("cdp_error(-32000):Browser.setDownloadBehavior: Not allowed"))
    }
}
