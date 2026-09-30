# AGENTS.md — AgentX entry point (read this first, then stop reading)

AgentX = Agora Android app + agent layer. Kotlin, Compose M3, Room, DataStore,
OkHttp/SSE, NDK (llama.cpp + proot prebuilts). Product flavors: `fdroid`
(test with `:app:testFdroidDebugUnitTest`), `play`.

## Start here (in order, then work)
1. `docs/MAP.md` — where everything lives (2 min).
2. `docs/MEMORY.md` — what was built, why, device rig, credentials policy.
3. `docs/LESSONS.md` — rules that cost real debugging hours (append-only).
4. `ARCHITECTURE.md` (upstream) — runtime/persistence deep dive.

## Build / test (always set `$env:ANDROID_HOME` first)
- Compile: `./gradlew :app:compileFdroidDebugUnitTestKotlin -x lint`
- Unit tests: `./gradlew :app:testFdroidDebugUnitTest` (~35 min full;
  `--tests` filters for iterations). Long runs: detached `.ps1` + poll file.
- Release: `./gradlew :app:assembleFdroidRelease` (needs proot prebuilts in
  `app/src/main/jniLibs/arm64-v8a/` — gitignored; recover from upstream
  release APK; `build-proot.sh` is Linux-only).
- Coverage gate: `LatexCoverageProbe` must stay 65/65. Suite must stay green.

## Hard rules
- Handwritten files you touch must stay ≤ 800 lines (`verifyKotlinFileSize`
  fails otherwise). New features → NEW files; prefs → `*PreferenceStore`.
- `SettingsManager.kt` / `SettingsRepository.kt` are at the cap: add ONE
  accessor line max, never new flows (see `SettingsAgentPreferenceStore`).
- New UI strings go in **every** `values-*/settings_strings.xml`
  (`SettingsResourceContractTest` enforces parity).
- `GenerationRequestBuilder` + `DebugLog.init` must stay strict-mock-safe
  (`runCatching` → defaults; never touch `applicationContext`).
- No raw `DropdownMenu` — use `AgoraDropdownMenu*` wrappers (contract test).
- NEVER print, commit, or log API keys. They live on-device only.
- Live shell path is `executeShellCommandEvents`; test changes on device
  (`adb install -r` preserves data), not just unit tests.
