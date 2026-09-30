# AgentX (mobile-ai-agora) — project memory for future agents

> Working copy lives at `_research/Agora` (clone of
> https://github.com/newo-ether/Agora.git). All feature work below is
> UNCOMMITTED-or-new vs upstream until stated otherwise. Device-test toolkit
> (adb scripts, screenshots, logs) lives at workspace root
> `mobile-ai-agora/` + `device-logs/` (NOT in the repo).

## What this is
Agora (renamed **AgentX** in-app, `app_name` in all locales) + a full agent
layer: universal math/physics/chemistry rendering, TypeSafe/Jev decisions,
TinyFish+DDG fusion search, Chat/Plan/Build modes, multi-model ensemble,
artifact PDF/MD export, env vars, key rotation, persistent FileLog.

## Feature map (file → purpose)
- `ui/components/LatexChemistry.kt` — `\ce`/`\mhchem`→`\mathrm`, arrows,
  `->[heat][cat]` conditions. `LatexPhysics.kt` — siunitx, braket,
  `\dv`, `\abs`, operators, env rewrites. Entry: `normalizeLatexForRender`.
  `MessageItemMarkdown.kt` — auto-`$` parsing. Default: single-`$` ON.
- `api/typesafe/TypeSafeClient.kt` — Jev wire format (`POST {base}/v1/systemone`,
  `GET {base}/v1/models`, Bearer, retries). `JevDecisions.kt` — fail-open
  helpers. `AnswerGuard.kt` — leak tripwire + synthesis cleaner.
- `api/monid/MonidClient.kt` — Monid REST (`POST /v1/run`, poll
  `GET /v1/runs/{id}`, terminal COMPLETED/FAILED/BLOCKED), TinyFish
  `/search`+`/fetch`, `fuseDedupe`.
- `tool/WebSearchToolProvider.kt` — `web_search` (tinyfish/fusion branches +
  Jev re-rank), `web_fetch`, `browse_page`.
- `viewmodel/GenerationContracts.kt` — `GenerationContext.agentMode`,
  `agentWorkspaceUri`, `agentModels`, `agentEnv`, `typeSafeApiKey/BaseUrl`.
- `tool/ShellToolDefinitions.kt` — plan-mode filter + `withAgentEnv` +
  `list_env`. `tool/ShellToolProvider.kt` — export applied in BOTH
  `executeShellCommand` AND `executeShellCommandEvents` (live path!).
- `tool/ArtifactToolProvider.kt` — `save_artifact` (md/pdf), `fetch_image`
  (verified decode). `tool/ArtifactExporter.kt` — block-model PDF renderer
  (title/headings/bold/code/bullets/tables+zebra/images/footers).
- `tool/EnsembleToolProvider.kt` — `ask_models` fan-out; needs `storedModelId`
  normalization (dialog once stored `Display:id:model` triples — fixed).
- `tool/CompactAssistToolProvider.kt` — `prune_context` (Jev verbatim prune).
- `data/SettingsAgentPreferenceStore.kt` — agent prefs slice (mode, workspace,
  models, encrypted env). `data/SettingsContracts.kt` — normalize/encode helpers.
- `api/ApiKeyRotation.kt` — round-robin pick + per-attempt failover;
  wired in `GenerationRequestBuilder`, `GenerationApiPathBuilder`,
  `BaseOpenAiProvider` (all 7 OpenAI-protocol providers inherit it).
- `util/FileLog.kt` — `filesDir/agentx-logs/session.log`, auto-start in
  `AgoraApplication.onCreate`, AI-parseable lines, 4MB rotation, share via
  Settings → Agent → Diagnostics log. `DebugLog` forwards everything.
- `ui/chat/bottombar/ComposerModeChip.kt` — Chat/Plan/Build chip in composer.
- `ui/settings/SettingsAgentPage.kt` — mode, workspace (SAF), ensemble models,
  Jev status, env vars, diagnostics share.

## Device testing (vivo I2011, Android 13, arm64 — adb id 3080607107000CJ)
- `adb install -r AgentX-release.apk` preserves data. `wm dismiss-keyguard`
  after reinstall. Stay Awake recommended.
- Navigate by `uiautomator dump` (see `uidump4.py`); screenshots only for
  visual checks; `send_text.py` types exact text (no shell quoting bugs).
- Render pulled PDFs with PyMuPDF (`fitz`) and view the PNGs.
- Evidence: `device-logs/` (TESTLOG.md rounds 1–3, FIXPLAN.md, ~30 screens).

## Credentials (NEVER print, commit, or log these)
- User owns all keys. Test keys were provided in chat for live verification
  only. Jev original key verified vs `api.typesafe.ai`; nara router verified
  Jev-compatible at `{base}/systemone` (NOT `{base}/v1/systemone`).
- Custom chat router + Jev-through-router + Monid search all configured
  ON THE DEVICE by the user — never in code or committed files.

## Build commands (set `$env:ANDROID_HOME` first)
- Compile: `./gradlew :app:compileFdroidDebugUnitTestKotlin -x lint`
- Tests: `./gradlew :app:testFdroidDebugUnitTest` (~35 min full; use
  `--tests` filters for iterations). Long runs: detached `.ps1` + poll.
- Release: `./gradlew :app:assembleFdroidRelease` (needs proot prebuilts in
  `app/src/main/jniLibs/arm64-v8a/` — gitignored; recover from upstream
  release APK if absent; `build-proot.sh` is Linux-only).
- Coverage gate: `LatexCoverageProbe` (65 formulas) must stay 65/65.
- Suite status 30 Sep 2026: 2875 tests, 0 failures.
