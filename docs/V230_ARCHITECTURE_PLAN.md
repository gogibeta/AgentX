# AgentX v2.3.0 — Full Architecture Plan (detailed)

**Date:** 2026-10-01 (rewritten with muse.ai browser research + full 6-network social research)
**Status:** Research complete. Big builds need user approval before starting.
**Research sources:**
- `~/workspace/research-muse-browser-report.md` — how muse.ai (Meta Muse) does visible browser automation
- `~/workspace/research-social-all-networks-report.md` — full FxEmbed worker inventory (all networks)
- `~/workspace/research-jev-report.md` — awesome-jev ecosystem
- `~/workspace/research-memory-report.md` — multi-key rotation + on-device memory
- Browser report (handoff 2026-09-30) — CDP/WebView/Custom-Tabs comparison

---

## PART 0 — Already fixed, uncommitted (test before commit)

| # | Bug | Fix | Files |
|---|---|---|---|
| 1 | `search_conversations`: multi-word queries returned nothing; scores hardcoded `1.0` | Per-word AND matching; real word-coverage score; `ScoredMessage` | `ConversationMessageSearch.kt`, `ConversationRepository.kt`, `RagToolProvider.kt`, `DrawerSearchState.kt` |
| 2 | `prune_context` → `{"error":"jev_unavailable"}` | Classified Jev errors (no key / 401 / 403 / 404 / 429 / network / bad response), human-readable, secret-free | `JevDecisions.kt`, `CompactAssistToolProvider.kt` |
| 3 | `save_artifact` fallback → `cacheDir` (Android can delete) | Fallback + image resolution now `filesDir/artifacts` | `ArtifactToolProvider.kt` |
| 4 | `web_fetch` 404 → undifferentiated `no_response` | Returns `http_error` + `http_status` + bounded body excerpt | `WebSearchToolProvider.kt` |
| 5 | TypeSafe listed as chat provider (rejects chat) | Removed from provider/model lists | `ProviderRegistry.kt` |
| 6 | Jev config buried in Providers screen | New Settings → Jev page: enable toggle, base URL field, model-name field (default `jev-latest`, no picker), multi-key encrypted store, bulk one-per-line paste, masked list, Test Connection | `JevPreferenceStore.kt` (new), `SettingsJevPage.kt` (new), `SettingsScreen.kt`, `SettingsTwoPane.kt`, `SettingsManager.kt`, `SettingsRepository.kt`, `GenerationRequestBuilder.kt`, `GenerationContracts.kt` |
| 7 | No bulk key import for providers | "Bulk import keys" per provider: one-per-line dialog, atomic single-write import (`addApiKeysBulk`), rotation spreads load automatically | `ProviderApiKeysSettings.kt`, `SettingsProviderDetailPage.kt`, `SettingsRepository.kt` |
| 8 | 17 new `jev_*` + 5 `provider_bulk_import*` strings | Added to all 10 `values-*/settings_strings.xml` | res/ |

**Known gaps in the uncommitted work (must fix before release):**
- `SettingsRepository.kt` is at 799 lines — under the 800 cap, but any further addition needs compaction.
- No migration from old TypeSafe provider keys/base URL → new Jev store.
- Jev key rotation is per-generation-context, not per-call; `TypeSafeClient` retries the same key.
- No unit tests yet for: search multi-word, Jev error classification, bulk import atomicity, Jev store encryption/rotation.
- `ArtifactExporter.kt` docs still mention the old cache fallback.

---

## PART 1 — Visible browser automation, Muse-style

### 1.1 What muse.ai actually does (research summary)

Meta launched **Muse** (Sept 8, 2026). Its browser automation is a **real, up-to-date Chromium** inside each user's dedicated cloud computer ("Muse Secure VM"), driven by a **browser sub-agent** through a **brokered CDP connection**. The sub-agent perceives pages via **accessibility-tree snapshots only** — no raw DOM, no page JavaScript, no DevTools. This is a deliberate **security boundary**, not just cost optimization.

**What the user sees (the UX we replicate):**
1. **Interactive Browser Widget** — a mini-browser *inside* the app showing the actual webpage rendered in real time (booking tickets, selecting seats, checkout — all live).
2. **"Take control of the browser" button** — user clicks it, types credentials themselves; **the agent fully pauses** during takeover.
3. **Screen-viewing tab** — dedicated tab to watch the agent's virtual computer live.
4. **Approval requests as system dialogs, not chat messages** — prompt-injected page text can't manufacture fake approvals.
5. **Full audit trail** of everything done and planned.

**How it works technically (patterns to copy):**
- **Two tiers:** negotiated API connectors (big services) vs. Chromium "appears as your activity" (everything else).
- **Sentinel:** a *separate* agent/process that is the **sole authority for network egress** (L4+L7 inspection). "Muse proposes; only Sentinel permits" — verdicts: allowed / denied / ask.
- **Credential surrogation** (`hatch-authd`): agent sees only **placeholder tokens**; real secrets injected just-in-time at the network boundary. Agent has "no visibility into passwords, including passwords a person types into the browser themselves." Agent pauses during autofill.
- **Payments:** single-use virtual cards scoped to merchant+amount+timespan.
- **Prompt-injection defense (5 layers):** model training → harness-level untrusted-input labeling → parallel classifier ensemble **outside the agent runtime** (DOM/image/file-based) → human approval for data leaving → deterministic kernel boundaries. $300k bug bounty ($130k for prompt injection).
- **Industry convergence:** real Chromium + AX-tree-first perception + trusted input path + user take-over for logins + placeholder credentials + out-of-band approvals + visible live view. Muse is the purest AX-only design; Operator uses screenshots→vision (expensive); browser-use/Skyvern use raw CDP with AX refs.

### 1.2 Our on-device equivalent (concrete mapping)

| Muse component | AgentX equivalent |
|---|---|
| Secure VM + Chromium | **Alpine/proot sandbox + Chromium 136** (already shipped — no new install) |
| CDP broker outside agent runtime | **Kotlin CDP client** (OkHttp WebSocket, JSON-RPC 2.0) — naturally outside model context; model only sees tool results |
| AX-tree-only sub-agent perception | `Accessibility.getFullAXTree` (viewport-only, truncated) as default; `Page.captureScreenshot` for hard pages + watch pane |
| Sentinel egress gate | Approval checks in the Kotlin tool layer (domain allowlist, confirm downloads/submits/payments) — no kernel eBPF needed on-device |
| hatch-authd surrogation | **Android Keystore-backed EncryptedSharedPreferences vault**; tool contract uses `cred_id` placeholders; Kotlin resolves + types via `Input.insertText`; values never enter model context, logs, or diagnostics |
| "Take control of the browser" | **Take-over mode:** pause agent loop, stream screenshots, forward touch as CDP input, native text-field overlay for typing; resume with live cookies |
| System-dialog approvals | **In-app approval dialog rendered outside the chat message list** (same anti-injection rationale) |
| Audit trail | Append every browser action (timestamp, action, target ref, URL) to session log + visible Activity list |

**What NOT to build:** WebView JS injection as primary hands (untrusted input, bot-detected, no CDP); Chrome Custom Tabs (zero automation surface); full-page screenshots (context cost); credentials in prompts.

### 1.3 Components to build

**0. Backend mode selection** (Settings → Browser) — NEW 2026-10-01, user requirement
- The user picks ONE backend; everything downstream (CDP client, tools, watch panel,
  audit, approvals) is identical — only the transport endpoint changes:
  - **Run locally** (default): sandbox Chromium on-device (component 1 below).
  - **Via tunnel**: the user pastes their own tunnel URL (e.g. the verified
    `https://agentx-browser.pcagent.workers.dev` from §1.6, or any CDP-over-HTTPS
    endpoint they run) + client token. No URL → tunnel mode cannot be enabled;
    the settings screen validates reachability (`GET /json/version` → 200) before saving.
- Client token lives in EncryptedSharedPreferences (never logged, never in diagnostics).
- Switching backends closes the old CDP session and starts a new one; the persistent
  profile (§1.3.1) is per-backend (local profile ≠ tunnel profile) and neither is ever wiped.

**1. Chromium launcher** (`browser/ChromiumLauncher.kt`, new file) — local backend
- `chromium --headless=new --remote-debugging-port=127.0.0.1:<port> --user-data-dir=<PERSISTENT profile dir>` inside the sandbox.
- proot shares the network namespace → `127.0.0.1:<port>` reachable from the app process.
- **No data loss — scry model (github.com/dataplanelabs/scry), adopted 2026-10-01:**
  - ONE persistent `--user-data-dir` under app-private storage (`files/browser-profile/`),
    NOT per-session temp dirs. Cookies, logins, localStorage, IndexedDB survive app restarts.
  - On startup, clear a stale `SingletonLock`/`SingletonSocket` left by an unclean shutdown
    so Chromium re-opens the existing profile instead of failing.
  - Exactly ONE long-lived Chromium process; its lifecycle is never tied to a task or a UI
    session. Never restart per task; never wipe the profile except via the explicit,
    confirm-gated **Settings → Browser → Clear browser data** button (user-initiated only).
  - Session-scoped cookies (no expiry) live in memory — mitigation is avoiding restarts:
    lenient health checks (below), never kill a healthy browser.
  - Downloads go to an app-owned dir that is never auto-cleaned.
- **CDP-stability hardening (scry, trace-learned):**
  - Chromium flags: `--disable-background-timer-throttling`,
    `--disable-backgrounding-occluded-windows`, `--disable-renderer-backgrounding`,
    `--disable-dev-shm-usage` (+ `--disable-gpu` on devices without GPU). A headful-but-never-foreground
    browser would otherwise be throttled/suspended and look unstable to the agent.
  - CDP socket keepalive: OkHttp WebSocket ping every ~20s; an idle CDP socket is the
    NORMAL state, never a dead one — never cut it; reconnect with backoff and reattach
    to the same target on genuine failure.
  - Health = Chromium process liveness (generous thresholds), not the wrapper script.
- **Security (scry): CDP is remote code execution.** Local backend binds `127.0.0.1` ONLY —
  never `0.0.0.0`. Tunnel backend requires user-supplied HTTPS/WSS URL + token; the app
  ships no default tunnel. Anyone reaching CDP can read the logged-in session and run JS
  as the user — treat the token like a password.
- Phase 0 (needs user go-ahead): `rm /etc/apk/cache/*.apk` (~340 MB, zero risk).

**2. CDP client** (`browser/cdp/`, new package)
- OkHttp WebSocket, JSON-RPC 2.0.
- Methods: `Target.createTarget`, `Page.navigate`, `Accessibility.getFullAXTree`, `DOM`/`Runtime` ref resolution, `Input.insertText` / `dispatchMouseEvent` / `dispatchKeyEvent` / `dispatchTouchEvent`, `Page.captureScreenshot` (JPEG q~60, viewport only), `Browser.setDownloadBehavior` (→ app-owned dir), `DOM.setFileInputFiles`, `Page.printToPDF`.

**3. Agent tools** (`tool/BrowserToolProvider.kt`, new file)
- `browser_navigate(url)` — Sentinel-style gate: domain allowlist, first-visit confirm.
- `browser_snapshot` — compact AX refs (`@e1`, `@e2`), viewport-only, truncated. Typical 2–8 KB.
- `browser_click @ref`, `browser_fill @ref <text|cred_id>`, `browser_key`, `browser_scroll`.
- `browser_screenshot` — reserved for hard pages (canvas, seat maps) + watch pane. ~1–1.8k tokens each at 720p.
- `browser_extract` — whitelisted extractor scripts only (model never runs page JS — Muse's boundary).
- `browser_download_status`, `browser_takeover` (pause/resume).

**4. Compose watch UI** (the muse.ai equivalent)
- Browser card/panel in/above the chat: **live screenshot stream (~2–4 fps, 720p JPEG)** + URL bar + page title + **one-line action narration** ("Clicked 'Add to cart' on example.com").
- Buttons: **Stop**, **Take over**, inline **Approve/Deny** when a gated action is proposed.
- This mirrors Muse's mini-browser widget + take-over button directly.

**5. Credential vault UI** (Settings → Credentials)
- Add site credential (site, username, password/TOTP) → Android Keystore, AES-256-GCM.
- Model sees only `cred_id`. JIT injection via `Input.insertText` (trusted path).
- Audit-log every use (site, field, timestamp — never the value).
- Composes with existing `ToolResultSecretRedactor`.

**6. Approval policy engine** (Kotlin tool layer)
- Domain allowlist (first-visit confirm), confirm on downloads / form submits / POST-with-personal-data / payments.
- Approvals as **dialogs outside the chat message list** (anti-injection, like Muse).
- Per-decision scope: once / this task / session / always.

### 1.4 Cost / performance expectations
- CDP round-trip on localhost: single-digit ms; screenshot capture+encode ~100–300 ms → 2–4 fps streaming is comfortable.
- AX-first keeps tasks at ~$0.20–0.40 (open-source benchmarks); screenshot-every-step costs 3–5× more.
- On-device: no per-user cloud VM cost; data never leaves the phone (strictly better than Muse's policy-based privacy).
- **Honest gap:** no kernel eBPF enforcement like Sentinel — our gate is the Kotlin tool layer. Same practical effect on a single-user device since the model never holds raw network capability.

### 1.5 Build phases
- **Phase 0:** Storage cleanup (apk cache) — needs user go-ahead.
- **Phase 1 (MVP):** Backend mode selection (Local/Tunnel settings + validation) + Chromium
  launcher (persistent profile, scry hardening) + CDP client (navigate, AX snapshot, click,
  fill, screenshot) + Compose watch panel (stream + Stop). Verify localhost CDP reachability
  on-device AND tunnel-mode reachability against the verified Worker URL.
- **Phase 2:** Trusted typing, key/scroll, download manager → app storage, file upload, extraction.
- **Phase 3:** Credential vault + `cred_id` contract + take-over mode (touch forwarding + native text overlay).
- **Phase 4:** Approval policies + audit trail + domain allowlist + multi-tab + Jev per-step browser loop (see Part 3, #3).

### 1.6 Cloud browser — VERIFIED 2026-10-01 (first-class "Via tunnel" backend)

Separate experiment (dummy GitHub account, never the release account): headless
Chromium on a GitHub Actions runner, exposed at one stable Cloudflare Worker URL.
**Verified end to end:** runner uplink online in ~20s; `GET /json/version` → 200
(Chrome/154.0.8037.57); `PUT /json/new` → 200; CDP WebSocket through the relay
navigated to example.com, page loaded, PNG screenshot captured (780×493).

Architecture (final, after 6 dropped tunnel providers):
- **No third-party tunnel.** Runner's `relay.py` bridges local CDP
  (127.0.0.1:9222) to a **Cloudflare Durable Object** (singleton
  `"browser-main"`, free-plan SQLite backend via `new_sqlite_classes`
  migration) over **one outbound WSS** (`/relay?secret=RELAY_SECRET`).
- Worker forwards client CDP HTTP (`/json/*`) + WebSocket (`/devtools/*`)
  with `?token=CLIENT_TOKEN` to the same DO — no isolate problem, no KV
  (DO tracks runner state itself), no public tunnel URL.
- Runner restarts are self-chained (each run dispatches its successor at
  ~4h50m, under GitHub's 6h kill); the stable URL persists and the DO swaps the
  uplink, but active CDP WebSockets are closed with code 1001 on replacement
  and must reconnect. A 30-min watchdog cron re-dispatches if the chain breaks.
- Free-tier budget: DO 100k req/day + 13k GB-s/day (hibernates while idle);
  well within limits. Cloudflare edge 403-blocks Python's stdlib UA —
  the app (okhttp/Dart) is unaffected, but any Python tooling must send a
  browser-like User-Agent.

Relevance to this plan: the app's CDP client (1.3) speaks the same protocol
to `wss://<worker>/devtools/...?token=...` as to localhost — so the cloud
browser is the **"Via tunnel" backend** in Settings → Browser (§1.3.0). The user
pastes the tunnel URL + client token; the app validates and uses it exactly like
the local backend. The 30-min watchdog keeps the chain alive independently.
Repo: `polironi10/agentx-cloud-browser` (PoC only).

---

## PART 2 — Social: all 6 networks via user-provided worker

### 2.1 The worker (user's correction incorporated)

The example worker (`fixtweet.twapiworker.workers.dev`) is a deployment of **FxEmbed** (MIT, github.com/FxEmbed/FxEmbed). **6 networks confirmed:** X/Twitter, Bluesky, TikTok, Instagram, Threads, **any Mastodon/ActivityPub instance**. NOT supported: Reddit, Facebook, YouTube. **Read-only on every network. No API key needed for reading anything** — the user deploys their own instance and pastes the URL into the app. The app ships with **no default worker URL**.

**User deployment (what the user does, app never touches Wrangler):**
1. `git clone https://github.com/FxEmbed/FxEmbed.git && cd FxEmbed && npm install`
2. `cp wrangler.example.toml wrangler.toml` → set Cloudflare Account ID
3. `npx wrangler login` (one-time), then `npm run deploy` → `https://<name>.<account>.workers.dev`
4. Free tier: 100,000 req/day. Optionally add credential pools via `wrangler secret put` to unlock gated routes.

### 2.2 Realm map (prefixes on `*.workers.dev`; prefixes DROP on custom domains — app must store which form validated)

| Prefix | Realm |
|---|---|
| `/api/2/...` | X/Twitter JSON API (16 paths) |
| `/twitter/...` | X embeds, RSS/Atom feeds, ActivityPub view, oEmbed |
| `/blueskyapi/2/...` | Bluesky JSON API (15 paths) |
| `/bluesky/...` | Bluesky embeds, RSS feeds, oEmbed |
| `/tiktok/...` | TikTok pages, `/api/`, `/raw/`, `/proxy`, oEmbed |
| `/instagram/...` | `/p/`, `/reel/`, `/api/`, oEmbed |
| `/atmosphere/2/...` | Multi-provider JSON: Mastodon, TikTok, Instagram, Threads (43 paths) |
| `/ai/2/...` | **Markdown twins — THIS IS WHAT THE AGENT CALLS** (`text/markdown`, token-lean, full text never truncated) |

OpenAPI specs for codegen: `/api/2/openapi.json`, `/blueskyapi/2/openapi.json`, `/atmosphere/2/openapi.json`, `/ai/2/openapi.json`.

### 2.3 Calling rules (agent MUST follow — encode in tool provider)

1. **User-Agent mandatory**: `AgentX/<version>`. Missing = HTTP 401. Bare library UAs may get edge-challenged 403.
2. **Embed HTML needs a BOT UA** (`Discordbot/2.0`, `Telegrambot`…); browser UA → 302 redirect.
3. **Pagination:** only via `cursor.bottom` from previous response. `count` 1–100. Cursors are opaque.
4. **Polling:** `?since=` on timelines → HTTP **204 empty** when nothing new (cheapest poll). RSS feeds as alternative.
5. **`?lang=xx`** inline translation where supported.
6. **Errors are envelopes** `{"code":N,"message":"…"}` with matching HTTP status. **404 envelope = valid "upstream has nothing"** — NOT a bug. `/ai` mirrors as `# Error` docs with fix tips.
7. **Error catalog:** 401 no-UA | 403 edge-challenge | 404 upstream-empty | **500** upstream failure OR missing credential pool OR logged-out IG block (normal, mirrors production) | **501** proxy-only route without account pool | 503 Bluesky upstream (retry) | 204 `?since=` nothing new.
8. **Freshness:** responses edge-cached (posts ~30d, timelines ~7d). **Always poll TIMELINES with `?since=`** for freshness.
9. Deleted/private posts in threads → **tombstone placeholders**, not errors.
10. `version` endpoints in every realm double as health checks → app Validate button hits `{base}/ai/version` (then `{base}/version` on custom domains).

### 2.4 Per-network endpoints + hard limits

**X / Twitter** — `/ai/2/status/{id}`, `/ai/2/thread/{id}`, `/ai/2/conversation/{id}`, `/ai/2/profile/{handle}`, `/ai/2/profile/{handle}/statuses`, `/ai/2/profile/{handle}/media`, `/ai/2/status/{id}/quotes`, `/ai/2/search?q=&feed=latest|top|media`, `/ai/2/trends`. 8 routes need a user-added credential pool (conversation, reposts, trends, typeahead, media/articles/followers/following/about) — surface honestly, don't fake.

**Bluesky** — `/ai/2/bsky/profile/{handle}`, `/ai/2/bsky/profile/{handle}/statuses`, `/ai/2/bsky/status/{handle}/{rkey}`, `/ai/2/bsky/thread/{handle}/{rkey}`, `/ai/2/bsky/search?q=`. Rkey recipe: `statuses?count=1` → copy rkey from `.../post/{rkey}`. Likes endpoints 4xx by design.

**TikTok** — `/ai/2/tiktok/status/{numeric-id}`, `/ai/2/tiktok/profile/{handle}`, `/atmosphere/2/tiktok/profile/{handle}/statuses`, `/hashtag/{tag}`, `/music/{id}`. **HARD LIMITS: numeric IDs only; strict IP rate limits (429 surfaces as 404 flaps — space out calls, NEVER retry-loop); no search/comments/likers.**

**Instagram** — `/ai/2/instagram/profile/{username}`, `/instagram/raw/{shortcode}`, `/atmosphere/2/instagram/profile/{user}/{statuses,videos,tagged,stories,followers,following}`. **Logged-out 500s are NORMAL; gated lists 501 without IG account pool.**

**Threads** — `/ai/2/threads/profile/{username}`, `/atmosphere/2/threads/profile/{user}/{statuses,replies,reposts,media,followers,following}`, `/conversation/{id}`, `/search?q=`, `/trends`. Search/trends/likes/follows proxy-only (501 without creds).

**Mastodon (any instance)** — `/atmosphere/2/mastodon/{domain}/{status/{id},thread/{id},conversation/{id},search?q=,profile/{handle},profile/{handle}/{statuses,media,followers,following}}`, `/ai/2/mastodon/{domain}/profile/{handle}`. **Fully keyless, needs nothing.**

**Universal:** `/ai/2/post?url={any-post-or-profile-url}` — ONE endpoint resolving all 6 networks. oEmbed in all embed realms. RSS/Atom feeds for X + Bluesky.

### 2.5 Agent tool set (register once, prefer `/ai` Markdown)

Cross-network: `social_resolve(url)` → `/ai/2/post?url=` · `social_link_preview(url)` → oEmbed JSON.

Per network (each backed by the paths above):
- X: `twitter_get_post`, `twitter_get_thread`, `twitter_get_conversation`, `twitter_get_profile`, `twitter_get_timeline`, `twitter_get_media`, `twitter_search`, `twitter_search_users`, `twitter_get_quotes`, `twitter_get_trends`, `twitter_monitor(handle, since)`
- Bluesky: `bluesky_get_post`, `bluesky_get_thread`, `bluesky_get_profile`, `bluesky_get_timeline`, `bluesky_search`, `bluesky_monitor`
- TikTok: `tiktok_get_video`, `tiktok_get_profile`, `tiktok_get_videos`, `tiktok_get_hashtag`, `tiktok_get_music`
- Instagram: `instagram_get_post`, `instagram_get_profile`, `instagram_get_posts`, `instagram_get_reels`
- Threads: `threads_get_post`, `threads_get_profile`, `threads_get_posts`, `threads_get_conversation`, `threads_search`, `threads_get_trends`
- Mastodon: `mastodon_get_post`, `mastodon_get_thread`, `mastodon_get_conversation`, `mastodon_get_profile`, `mastodon_get_timeline`, `mastodon_get_media`, `mastodon_search`, `mastodon_get_reposts`

### 2.6 Settings UI (Settings → Social)

- **Read worker base URL** field (starts empty) + "Deploy your own" help link (docs.fxembed.com/deployment).
- Optional **write worker base URL + auth token** (masked) — "only needed for posting".
- **Validate button** → `GET {base}/ai/version` with `User-Agent: AgentX/<version>`; fallback `{base}/version`; records which realm-prefix form worked.
- Enable toggle; last-validated status line.
- Per-network enable toggles (all 6, default on).

### 2.7 Write path (separate, later)

FxEmbed can't post. Posting = separate tiny worker the user deploys, holding credentials as **worker secrets** (`wrangler secret put` — never in the app):
- X: `POST /tweet {text, reply_to?}` → `api.twitter.com/2/tweets` with OAuth 1.0a (free tier ≈ 17 posts/24h).
- Bluesky: AT Protocol app password (no worker strictly needed).
- Mastodon: instance domain + access token. Threads: Meta Threads API user token.
- App stores ONLY worker URL + bearer token. **Every post requires explicit user confirmation in chat.**

### 2.8 Security
- Write-worker bearer token treated like an API key (register with `ToolResultSecretRedactor`; never in logs/diagnostics/transcripts).
- Raw social API keys/secrets live ONLY in the user's worker — app never asks for, accepts, or stores them.
- Strict URL validation (`https://` only, no credentials in URL).
- Don't bulk-archive other users' content into memory without user direction.

---

## PART 3 — Jev: what to build, in order

### 3.1 API recap (`POST /v1/systemone`, Bearer auth)
- **Noul:** yes/no → `{ "noul": 0.92 }` (P(yes); near 0.5 = genuinely uncertain).
- **Choice:** criteria map (≤255 options) → `{ "choice": "winner", "probabilities": {...}, "confidence": 0.82 }`.
- **Score:** 2–10 ordered levels → probability-weighted mean.
- **Batch aggressively:** all questions in one request answered in parallel (~70–500 ms); 10th question ≈ free latency.
- ~32k input tokens; ~$0.042/1M input tokens, output free; errors 401/422/429/529.
- Rules: state = evidence, instructions = judgment, criteria = answer space. **Deterministic code first, Jev for the gray zone.** Fail-open for quality, fail-closed for safety. Calibrate thresholds on small labeled fixtures. Shadow mode first.

### 3.2 Build order (value/effort, from awesome-jev ~630 entries)

**#4 — Memory & search-result reranking (LOW-MEDIUM effort, quick win).**
After BM25/keyword retrieval, one batched request: `Noul` per candidate "does this contain evidence useful for the current query?" Sort by probability, drop below threshold (hippo-memory: R@1 0.41→0.62). Also improves existing web-search re-rank. Fail-open: keep original order on error.

**#1 — Jev context pruning (MEDIUM effort, biggest user pain).**
Per transcript segment/tool-call: `Choice` keep/drop or `Noul` "will the agent plausibly need this later?" over compact shared state (task summary + recent turns). Keep survivors **verbatim** (never re-summarized — preserves prompt cache); drop → expandable store behind `expand()` pointer, never deleted. Auto-run at context watermark and between tasks. Fail-open: on error, keep everything. This is the real fix for `prune_context` + the context-growth complaint.

**#7 — Completion verification gate (LOW effort).**
Stop-hook `Noul`: "given the transcript evidence, is the task actually complete?" Cheap — only when the claim lacks visible evidence. Fail-open: trust claim, log verdict.

**#2 — Tool-call risk gate (MEDIUM effort, safety).**
Before shell/browser/file-write: batched `Noul`s (destructive? irreversible? exfiltrates? matches task?) + `Choice` allow/ask/deny. Deterministic rules first (read-only auto-allow; `rm -rf /` auto-ask); Jev judges gray zone. Ask → one-tap in-app confirm; deny → logged reason; low confidence → ask. **Fail-closed on error.**

**#5 — Tool relevance pre-selection (MEDIUM effort).**
Each turn, Jev `Choice` over tool set → bias/prefilter tools in prompt. Fewer wrong calls → less context bloat. Fail-open.

**#6 — Model/effort routing (MEDIUM effort).**
Per request, `Choice` over (model, effort) pairs from task summary — trivial → cheapest key, hard → strongest. Composes with key rotation: **Jev picks the tier, rotation picks the key**. Fail-open: default provider.

**#3 — Browser per-step Jev loop (HIGH effort, explicit user ask).**
Per browser step: `Choice` over candidate actions from pruned AX tree (jev-ultrafast pattern); `Noul`s "goal reached?" / "stuck/looping?" veto premature DONE/BLOCKED; LLM only types text. Shown live in the watch panel. Fail-open: escalate to LLM on error/low confidence.

**Order:** #4 → #1 → #7 → #2 → #5 → #6 → #3.

### 3.3 Open-weight fallback (later)
`/v1/systemone`-compatible drop-ins exist (WebJev Apache-2.0 Qwen3.5-35B fine-tune beat Jev 1.13 on browser tasks 38.5% vs 16.7%; `jev-local`, `ruling`, `Luce`, `Verdict` via ONNX). Useful as self-hosted fallback later — not now.

---

## PART 4 — Multi-key rotation: one generic KeyPool

### 4.1 Design (industry pattern: LiteLLM Router, one-api, Hermes)
- **Pool, not primary+backup.** Per (provider, base URL): `List<ApiKeySlot { id, maskedLabel, encryptedKey, cooldownUntil, consecutiveFailures, disabled }>`.
- **Default strategy: random among healthy keys** (LiteLLM ships `simple-shuffle`; exactly what the user wants — spreads load, no corruptible state).
- **429 handling (key insight): fail over first, back off last.** On 429: mark key cooled (honor `Retry-After`, default ~60s), **immediately retry same request on a different healthy key** — no sleep. Only when ALL keys cold: jittered backoff, one more pass, then loud error ("all 5 keys rate-limited, retry in ~40s").
- **Error classification:** 429/408/5xx/timeout → different key · 401/403 → disable key + alert user (masked `sk-…a1b2`) · 400/404/422 → abort immediately (request is wrong, don't burn keys).
- **Recovery automatic:** cooldowns expire; success resets counters.
- **Thread-safety:** `Mutex`/`synchronized`; `AtomicInteger` for round-robin.
- **One component, used twice:** provider keys AND Jev keys (same bulk/rotation UX the user asked for).
- **Storage:** DataStore encrypted at rest (existing HKDF `conch-agora-v2`). Never log raw keys.
- **UI:** bulk textarea (one per line) → masked rows with health dots (healthy / cooling down / invalid). No manual selection anywhere.

### 4.2 Status
Backend rotation exists (`ApiKeyRotation.kt`: round-robin + failover). Bulk UI added (uncommitted). **Still needed:** per-key cooldown/429 tracking, random-among-healthy strategy, `Retry-After` honoring, per-call (not per-generation) Jev key rotation, `TypeSafeClient` alternate-key retry.

---

## PART 5 — Memory, context growth, compaction

### 5.1 Why context grows super fast (diagnostics-confirmed + research)
1. **Tool results are the #1 bucket** — every call + result re-sent every turn.
2. Bloated/overlapping tool definitions re-sent every turn.
3. Full history replayed every turn, no windowing.
4. Entry-count compaction triggers → premature recompaction.

### 5.2 Design: local-first, Room = immutable source of truth
- **Compaction NEVER touches the database.** It only rewrites the live context window. Full history preserved forever (user's hard requirement).
- **FTS5 external-content index** on messages: BM25-ranked, sub-ms queries, ~30–50% text overhead, **zero new dependencies**. ~10–20 MB/year heavy use. **No embeddings** (90 MB model violates "very very small but strong"). **No cloud DB** (offline, privacy, latency, sync complexity). Encrypted export covers portability.
- **Compaction pattern:** trigger at ~75–80% of context window (**tokens, not message counts**). Fork summarizer → structured summary (goal, decisions, files, errors, pending, verbatim user directives). Live = `[rolling summary][last 10–20 messages verbatim]`. Store in `conversation_summaries` keyed to `summarized_through_message_id` (resumable chain).
- **Lessons table** (the user's `lesson.md`, made real): discrete facts extracted by summarizer ("user prefers X"), separately searchable, with `use_count`/`last_used`.
- **Tool outputs: hard-truncate, don't summarize** (summarizing invalidates prompt cache and re-bills; capping cuts per-turn cost ~38%).
- **One `memory_search` tool:** FTS MATCH (tool builds syntax, never the model), ±2 message context expansion, lessons merged first. Plus `memory_stats` (counts, index size, last compaction) for "is it working?" debugging.

### 5.3 Schema (Room)
- `messages`: id, conversation_id (indexed), role, content, tool_calls_json, created_at, **token_count** (cached at insert). Append-only.
- `messages_fts`: FTS5 virtual, external content, `tokenize='unicode61 remove_diacritics 2'`, 3 sync triggers.
- `lessons`: id, text, category, source_conversation_id, created_at, use_count, last_used (+ tiny FTS).
- `conversation_summaries`: conversation_id, summary_text, summarized_through_message_id, created_at.
- `conversations`: add `total_tokens`, `last_compacted_at`.

### 5.4 Diagnostics: token-bucket logging
Per LLM call, log **token-bucket breakdown** — `system / toolDefinitions / history / toolResults / output` — plus per-message cached `token_count`. "Why is context growing" becomes a one-glance answer. This is the user's explicit ask: "make the log better so the exact cause can be traced."

---

## PART 6 — Visible diagnostics / Share Logs (upgraded 2026-10-01)

- User hates the hidden 7-tap Developer Options flow.
- v2.2.0 has always-on logging: `filesDir/agentx-logs/session.log` (+ rotation to `session.1.log`).
- **Add Settings → Share diagnostics/logs** (one-tap, visible): export redacted JSON or summary text via system share sheet. No gesture needed.
- Keep the 7-tap path as an extra, not the only way.

### 6.1 Much-better logging design (user ask 2026-10-01: "make the log and debugging even much better")

One structured event pipeline feeding the existing `DiagnosticEventBuffer` — every
event carries `ts, category, name, session_id, duration_ms, outcome, detail_json`
(never prompts, conversation text, tool args/results, URLs, bodies, or secrets —
`DiagnosticRedactor` + `ToolResultSecretRedactor` enforced at the write site):

- **`browser` category:** every CDP action — `navigate/click/fill/screenshot/...`
  with target ref, page URL (domain only in exports), duration, outcome; backend mode
  (local/tunnel), reconnect events, keepalive failures. This IS the audit trail (§1.3.6):
  the Activity list reads from it.
- **`llm` category:** per call — model id, provider, key fingerprint (masked, e.g. `…a3f9`),
  token-bucket breakdown (`system / toolDefinitions / history / toolResults / output`),
  latency, retries, 429/cooldown events, Jev rerank/prune decisions. "Why is context
  growing" becomes a one-glance answer (§5.4).
- **`tool` category:** tool name, duration, outcome, error class (no args/results).
- **`net` category:** FxEmbed/worker calls — endpoint realm, HTTP status, error envelope
  code (no bodies); tunnel health checks.
- **`sys` category:** app start, sandbox install, OOM/low-storage warnings, profile-lock recovery.

Debugging UX:
- **Settings → Diagnostics → Live event tail:** scrolling list, filter by category,
  tap an event for its detail JSON. Works offline, reads the ring buffer.
- **Session export:** the one-tap share from above now bundles `session.log` +
  structured events JSON + redacted summary (counts by category/outcome, top slow tools).
- **Debug overlay (optional toggle):** floating token-rate + last-action chip during
  agent runs, for the user's "is Jev working / what is it doing" question — the overlay
  shows live `llm` events (model, tokens/s, Jev decision) with zero chat pollution.

---

## PART 7 — Remaining reported bugs

- **`ask_models` provider_error** on `space-bunny-alpha` / `space-bunny-alpha-bynara` (Agnes models worked): investigate model-ID/provider-config — likely a bad model ID or provider routing issue, not a code bug. Needs reproduction.
- **`file_grep` directory handling inconsistent:** fix to walk directories recursively with clear relative-path output.
- **`curl` absent:** add to sandbox or document the `web_fetch` alternative.
- **CI watcher** (`agentx-ci-watch-12min`): still diagnose-only. Needs user decision on autonomous fix+repush.

---

## PART 8 — Build order (user-approved 2026-10-01: "implement all")

1. **Test + commit current fixes** (Part 0): unit tests for search/Jev-errors/bulk-import; fix the known gaps; commit; push.
2. **Browser MVP** (§1.3.0–1.3.4): backend mode selection (Local/Tunnel) + persistent
   profile (no data loss, scry hardening) + CDP client + tools + watch panel.
3. **Credential vault + approval gates** (§1.3.5–1.3.6) + browser audit trail.
4. **Upgraded diagnostics** (Part 6): structured browser/llm/tool/net/sys event log,
   live tail, debug overlay, one-tap Share Logs.
5. **Social read integration:** Settings → Social page + worker validation + all-6-networks tool provider (Part 2).
6. **KeyPool** with 429/cooldown health tracking (provider + Jev keys).
7. **Memory:** FTS5 + lessons + `memory_search` + `memory_stats` + token-bucket diagnostics + compaction that preserves the DB.
8. **Jev #4** (rerank) → **#1** (pruning) → **#7** (verify gate) → **#2** (risk gate).
9. **Browser Phases 2–4**, social write path, Jev #5/#6/#3.

---

## Compatibility / security constraints (unchanged)

- Signing key: `/home/hatch/workspace/user/files/debug.keystore`, alias `androiddebugkey` — never rotate.
- Preserve: `applicationId com.newoether.agora`, HKDF `conch-agora-v2`, JNI paths, Room DB name, keystore alias, notification IDs, sandbox paths, `.agora`/`Agora_backup_*` import.
- Never request/repeat/log secret values. Diagnostics never log prompts, conversation text, tool args/results, URLs, bodies, or secrets.
- Handwritten Kotlin ≤ 800 lines; `SettingsManager`/`SettingsRepository` at cap — new flows go in new files (`*PreferenceStore` pattern).
- New strings in ALL `values-*/settings_strings.xml`. No raw `DropdownMenu` — `AgentXDropdownMenu*` wrappers.
- `GenerationRequestBuilder` + `DebugLog.init` stay strict-mock-safe.
