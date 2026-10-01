package com.newoether.agora.data

/**
 * Wipe hook for the persistent browser profile (cookies, logins, site storage).
 *
 * Implemented by stream A on [com.newoether.agora.browser.ChromiumLauncher]
 * (`clearBrowserData()`); the settings UI only ever calls through this
 * interface, so the UI compiles and ships before the launcher wiring lands.
 */
interface BrowserDataController {
    /** Wipes the persistent browser profile. User-initiated only; never called automatically. */
    suspend fun clearBrowserData(): Boolean
}
