# LESSONS.md — hard-won rules for this repo (append-only, never rewrite)

## Build / test
- Gradle builds take 1.5–9 min; full unit suite ~35 min on this machine. Long
  `bash` calls get killed by the tool wrapper → launch detached via a `.ps1`
  script (`Start-Process powershell -File ...`) and poll the output file.
- `verifyKotlinFileSize`: handwritten files modified-vs-HEAD must stay ≤ 800
  lines. New features go in NEW files; giant prefs surfaces get the
  `*PreferenceStore` slice pattern (see `SettingsAgentPreferenceStore`).
- NEVER edit `SettingsManager.kt` / `SettingsRepository.kt` beyond the cap:
  they sit at ~800 lines; any addition trips `new_oversized_source`.
- Robolectric 4.16 CANNOT construct `android.graphics.pdf.PdfDocument`
  (`startPage` throws "document is closed" even on a fresh instance) → keep
  PDF tests to pure layout/parse functions; verify real PDFs on device with
  PyMuPDF rendering.
- Editing `res/values-*/` mid-test-run can deadlock the test worker; batch
  locale edits when Gradle is idle. New strings MUST go in ALL locales —
  `SettingsResourceContractTest` enforces key parity.
- PowerShell mangles `adb exec-out` binary screenshots and nested quoting →
  `screencap -p /sdcard/x.png` + `adb pull`, and drive device text via a
  Python subprocess script (single argv, sh single-quotes need no escapes).

## Kotlin gotchas that actually bit
- `Regex.replace` interprets `\r` in the REPLACEMENT (`\rightarrow` loses its
  backslash) → use literal `String.replace` or `Regex.escapeReplacement`.
- `$` inside Kotlin strings needs `\$` (tests too: `"\$x"`).
- `companion object` is illegal inside `object` (use private top-level vals).
- `ModelId.apiModelName` is an EXTENSION (`import ...apiModelName`), not a member.
- `requireNotNull(x, msg)` needs a lambda message; `buildJsonArray` needs
  explicit `add` import; `Paints`-style private types can't leak via internals.

## Architecture truths (device-proven 29–30 Sep 2026)
- The LIVE shell path is `executeShellCommandEvents`, not `executeShellCommand`
  (which only serves background jobs). Any shell behavior change needs BOTH.
- `GenerationRequestBuilder.resolveProviderKey` + `agentSnapshot()` must be
  mock-safe (`runCatching` → defaults): strict mockk tests stub
  `resolveActiveKey()` only, never the StateFlows.
- `DebugLog.init` must not touch `context.applicationContext`: strict Context
  mocks stub `applicationInfo` only.
- Custom-model IDs are `providerId:model`; the Agent dialog must store values
  as-is, never re-prefix with the display name (router 404s on triples).
- `TypeSafeClient.canonicalBaseUrl` strips one trailing `/v1`: official base
  `api.typesafe.ai` and router bases `.../v1` both resolve correctly.
- `PdfDocument` pages go stale after `finishPage`: draw helpers must take a
  `pageProvider: () -> Page` lambda, never a captured page.
- Local Sandbox needs `libproot_exec.so` + loader + talloc from CI
  (`build-proot.sh` runs on Linux only). No WSL/make here → recover prebuilts
  from the upstream release APK into gitignored `app/src/main/jniLibs/`.
- `adb install -r` preserves all user data (same debug signature). Never
  `uninstall` a user's phone to reinstall.
- `uiautomator dump` beats screenshots for navigation (text+coords); screenshots
  only for VISUAL verification (rendering, PDF pages via PyMuPDF).
- Dismiss the keyguard (`wm dismiss-keyguard`) after reinstall; enable Stay
  Awake in dev options to stop the phone locking mid-test.
- Sandbox toolchain (2026-10-01 PDF saga): the Alpine sandbox ships with NO
  python3 and a possibly-stale apk index — the agent must run `apk update`
  before `apk add`, and there is NO C compiler, so numpy/matplotlib/scipy can
  never build from source. Prefer pure-Python Alpine packages
  (`apk add py3-pillow`) or pip wheels. The shell tool description now says this.
- `save_artifact` could not register a pre-built binary (agent-generated PDFs
  via reportlab were invisible to the app). It now accepts `source_path`: a
  plain file name in the agent workspace, registered as-is (up to 50MB).
- `ask_models` swallowed provider failures as opaque "provider_error"; it now
  returns the real (truncated) provider message, and accepts a per-call
  `models` array to ask specific models for one verification pass.
- CDP "all backends give cdp error" (2026-10-02): the page *session* died
  while the *socket* stayed alive (renderer crash, tab closed, tunnel-runner
  restart/DO uplink swap, WebView recreated). `ensureConnected()` only checked
  the socket, so every later `invoke()` failed forever with a bare
  `cdp_error(-32000)`/`-32001` — and `BrowserToolProvider.errorCode()` truncated
  even the message away, so the agent saw only the numeric code. Fix:
  `CdpClient.invoke()` now detects session-invalid signatures, re-attaches
  (or recreates the page target) and retries once; errors carry
  `cdp_error(code):method: message` plus backend tag, and tool errors include
  full `message` + actionable `hint`. Never truncate a diagnostic error to a
  bare code again — the code alone is undebuggable.
- `BrowserSession` now records WHY each backend connect failed
  (`lastConnectFailure()`); `not_connected` tool errors surface that reason
  instead of an empty message. Always thread connect-failure reasons to the
  caller — "not connected" with no cause wastes a full debug round-trip.
- CDP stale-socket race (2026-10-02): `WebSocketListener.onClosed` fired for a
  PREVIOUS socket after `connectLocked()` had already installed its
  replacement; `handleSocketGone()` nulled the live socket and failed all of
  its pending calls. Fix: pass the firing `WebSocket` into the handler and act
  only on identity match (`socket !== gone → ignore`). Any socket-death
  handler must identify WHICH socket died.
- CDP page recreation vs reattach (2026-10-02): `reattachLocked()` returned a
  bare Boolean for both "re-attached to the same target" and "target gone,
  page recreated" — but only the latter invalidates cached DOM node ids.
  Returning `ReattachResult { REATTACHED, RECREATED, FAILED }` and firing
  `onPageRecreated()` lets `BrowserSession` drop `snapshotRefs`, so the next
  click/fill fails fast with "take a new snapshot" instead of a mystery
  node-not-found. Never conflate "reconnected" with "same page".
- `FileLog.tailLines()` read the rotated file first and stopped when the
  limit filled, so a long previous-session log crowded out the current
  session's newest lines. Fix: aggregate rotated → current → queued, then keep
  only the newest `maxLines` overall.
- Tunnel failure messages must be host-only: the stored tunnel URL may carry a
  user-pasted `?token=` query param — never interpolate the raw URL into a
  reason that reaches logs or tool results.
- WebView target-scoped CDP sessions (2026-10-03): `connectWebView()` opens a
  target-level WebSocket (`/devtools/page/<id>`) where the connection IS the
  page session — no `Target.attachToTarget`, no session id. But `CdpClient`
  kept the stale `targetId`/`sessionId` from the previously used backend, so
  every command went out with a dead session id and failed
  `cdp_error(-32001): Session with given id not found`; the self-healing
  reattach then failed too because `Target.*` is invalid on a target-scoped
  connection. Fix: `CdpClient.clearPageSession()` called right after the
  WebView connect. Rule: whenever you connect to a target-scoped endpoint,
  explicitly drop inherited page-session state — never assume a fresh
  `CdpClient` — and never run the browser-level reattach path against it.
- GeckoView `WebExtension.InstallException.code == -1` is the generic/unknown
  install failure, and `GeckoRuntime.create()` throwing a bare
  `IllegalStateException("Failed to initialize GeckoRuntime")` is not
  diagnosable. Fix: preflight now logs the cause chain and the full stack, so
  the next log bundle says WHY (ABI, omni.ja, profile dir). Never log only an
  exception's `toString()` when the cause is the actual diagnostic.
- Watch-frame polling stops after 5 consecutive failures but restarts on the
  next browser tool event; a degraded tunnel runner (long-lived GitHub runner,
  >30s screenshot latency) therefore looks like "no live view". The timeout
  is the symptom — check runner health before blaming the app.
- Compose AndroidView double-parent crash (2026-10-03): the browser watch
  panel and the fullscreen dialog both hosted the SAME live WebView/GeckoView
  instance via `AndroidView(factory = { liveView })`. When fullscreen toggled,
  the dialog's holder was created before the panel's holder was disposed (or
  vice versa) and `addView()` threw "The specified child already has a parent",
  crashing the app. Fix: EVERY factory that returns a shared view must
  defensively `(liveView.parent as? ViewGroup)?.removeView(liveView)` before
  returning it — on both sides, since disposal/creation ordering is not
  guaranteed. Never assume the other holder released the view first.
- Missing tool, not a broken tool (2026-10-03): the in-app agent could not
  fetch an X account's tweets because only `social_resolve`/`social_search`
  existed — the code hint told it to use the worker's
  `/ai/2/profile/{handle}/statuses` route but no tool called it, so the agent
  fell back to login-walled direct fetches. Fix: added `social_timeline`.
  When a hint references a capability, the tool that performs it must exist.
