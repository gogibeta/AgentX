# Browser Skill — AgentX In-App Browser

Use this skill before any browser automation task. It covers engine selection,
the browser tools, and the failure modes seen in production.

## Engines (Settings → Browser → Browser engines)

| Engine | When to use | Requirements |
|---|---|---|
| **System WebView** (recommended) | Lightest, visible, touchable | Built into Android — nothing to install |
| **GeckoView** | Non-Chromium engine, fast element tables | Built into the APK — nothing to install |
| **Chromium (sandbox)** | Full Chromium, heaviest | Alpine sandbox installed (tap **Install** on the engine row) |
| **Cloud tunnel** | Heavy pages, logins, downloads | Your Cloudflare relay URL + client token, both validated (green check) |

Pick the active engine with the radio button. The agent uses whichever is selected.
- **Uninstall** on the Chromium row deletes the Alpine sandbox to free ~1 GB.
  This also disables the **shell tool** (they share the sandbox) — reinstall
  from the same row with **Install** to restore both.
- If an engine shows **Error**, tap Install/Validate again — retry is safe.

**If all engines fail:** do NOT retry blindly. Check Settings → Browser →
Diagnostics for the engine-specific error. Each engine fails differently;
the diagnostics panel tells you which one and why.

## Engine notes

- **System WebView:** renders visibly in the watch panel and is touchable
  (take-control). The agent sees the page; screenshots are only for the
  watch-panel preview, not the agent loop.
- **GeckoView:** the agent gets a structured element table (`[1] button "Search"`)
  pushed after page load — no screenshots in the agent loop. Click/fill use
  element refs, not coordinates.
- **Chromium (sandbox):** needs the Alpine sandbox. The launcher polls
  `http://127.0.0.1:9333/json/version` for ~30s. `connect_failed` almost
  always means the sandbox was uninstalled — reinstall from the engine row.
- **Tunnel:** paste the relay URL (`https://<your-worker>.workers.dev`, no
  trailing slash) and client token, tap **Validate** (probes
  `GET {url}/json/version?token=...`). A 401 means the token is wrong; a 404
  means the URL is wrong. The app re-checks `/json/version` with your token
  before every connect; `tunnel_unreachable` means the relay runner is offline
  — restart the runner, don't change the token.

### The browser tools

- `browser_navigate(url)` — load a page. Prefer this over guessing deep links.
- `browser_snapshot()` — accessibility/element tree of the current page. Read
  this before clicking/typing; it gives you element refs.
- `browser_click(ref)` / `browser_fill(ref, text)` / `browser_key(key)` /
  `browser_scroll(direction)` — interact using refs from the snapshot.
- `browser_screenshot()` — visual check. Use when layout matters or a snapshot
  looks wrong. (Not needed with GeckoView — the element table replaces it.)
- `browser_takeover` — hands control to the user (logins, CAPTCHAs). The agent
  loop pauses; resume after the user finishes.
- `browser_download_status` — check a download started by the page.

### Rules

- **Logins and CAPTCHAs:** never type credentials yourself. Use
  `browser_takeover` and let the user log in. The CredentialVault holds
  secrets; the agent only sees credential IDs.
- **Downloads:** sandbox/tunnel downloads stay on their own end; WebView and
  GeckoView downloads land in the app download dir and are registered
  automatically.
- **When the browser is unnecessary:** `web_fetch` and `browse_page` are
  independent of all engines and work even when the browser is down. For
  reading articles, docs, or simple pages, prefer them — they are faster
  and cannot fail the way CDP can.
- **One engine at a time:** switching engines closes the session. Finish or
  abandon the current page before switching.
