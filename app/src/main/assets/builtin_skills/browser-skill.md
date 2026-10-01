# Browser Skill — AgentX In-App Browser

Use this skill before any browser automation task. It covers backend selection,
the 9 browser tools, and the failure modes seen in production.

## Backends (Settings → Browser)

| Backend | When to use | Requirements |
|---|---|---|
| **Tunnel** (recommended) | Heavy pages, logins, downloads | Your Cloudflare relay URL + client token, both validated (green check) |
| **Local** | Offline use, no relay | Alpine sandbox + Chromium installed (`apk add chromium`) |
| **System WebView** | Lightest, no downloads | Android System WebView installed and enabled |

**If all three fail:** do NOT retry blindly. Check Settings → Browser → Diagnostics
for the backend-specific error. The three backends fail for different reasons;
the diagnostics panel tells you which one.

### Tunnel backend — setup that actually works

1. Deploy the Cloudflare Durable Object relay (see the repo's cloud-browser guide).
2. In Settings → Browser → Tunnel, paste the relay URL
   (`https://<your-worker>.workers.dev`) and the client token.
3. Tap **Validate** — it probes `GET {url}/json/version?token=...` and must return
   the Chrome version string. A 401 means the token is wrong; a 404 means the
   URL is wrong (probe the `/json/*` path, never the bare root).
4. Only then switch the backend to Tunnel.

The app health-checks `/json/version` with your token before every connect.
If the relay runner is offline, you get `tunnel_unreachable` — restart the
runner, don't change the token.

### Local backend — setup that actually works

1. The Alpine sandbox must exist (Settings → Sandbox shows it installed).
2. Inside the sandbox: `apk update && apk add chromium` — the browser does NOT
   ship Chromium; it must be installed once.
3. The launcher polls `http://127.0.0.1:9333/json/version` for up to ~30s.
   `connect_failed` with backend `local` almost always means Chromium is not
   installed or died on startup — check `command -v chromium` in the sandbox.

### The 9 tools

- `browser_navigate(url)` — load a page. Prefer this over guessing deep links.
- `browser_snapshot()` — accessibility tree of the current page. Read this
  before clicking/typing; it gives you element refs.
- `browser_click(ref)` / `browser_fill(ref, text)` / `browser_key(key)` /
  `browser_scroll(direction)` — interact using refs from the snapshot.
- `browser_screenshot()` — visual check. Use when layout matters or a snapshot
  looks wrong.
- `browser_takeover` — hands control to the user (logins, CAPTCHAs). The agent
  loop pauses; resume after the user finishes.
- `browser_download_status` — check a download started by the page.

### Rules

- **Logins and CAPTCHAs:** never type credentials yourself. Use
  `browser_takeover` and let the user log in. The CredentialVault holds
  secrets; the agent only sees credential IDs.
- **Downloads:** local-backend downloads land in the sandbox download dir and
  are registered automatically. Tunnel-backend downloads stay on the remote
  runner — they do NOT come back to the phone by themselves.
- **When the browser is unnecessary:** `web_fetch` and `browse_page` are
  independent of all three backends and work even when the browser is down.
  For reading articles, docs, or simple pages, prefer them — they are faster
  and cannot fail the way CDP can.
- **One backend at a time:** switching backends closes the CDP session. Finish
  or abandon the current page before switching.
