<div align="center">
  <img src="app/src/main/assets/agentx_transparent_large.png" alt="AgentX Logo" width="120" />

  # AgentX

  **Your own AI, on your own terms.** A BYOK Android client for frontier LLMs with
  multi-provider access, tree-structured conversations, agentic tools, local models,
  and remote device control.

  [![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
  [![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
  [![Kotlin](https://img.shields.io/badge/Kotlin-2.x-blue.svg)](https://kotlinlang.org/)

  [Download APK](https://github.com/gogibeta/AgentX/releases) · [User Manual](https://gogibeta.github.io/AgentX/) · [Report an issue](https://github.com/gogibeta/AgentX/issues)

  <img src="assets/feature_graphic.png" alt="AgentX — a BYOK AI app that takes back your data sovereignty." width="100%" />
</div>

> AgentX is an independent continuation of [Agora](https://github.com/newo-ether/Agora)
> by newoether, rebranded and extended with an agent layer. Upstream is credited below;
> everything else in this file describes AgentX.

## Download

Get the APK from **[GitHub Releases](https://github.com/gogibeta/AgentX/releases)** and
install it on your device (Android 8.0+). Every release APK is signed with the same
key, so updates install cleanly over the existing app — your API keys, conversations,
and settings are preserved. No uninstall needed, ever.

> [!TIP]
> Updating with `adb`: `adb install -r AgentX-<version>.apk` keeps all app data.

## What it does

AgentX is an open-source Android client for using **your own model accounts and
endpoints**. Conversations stay in local on-device storage; requests go directly to
the provider you choose. It supports non-linear message branches, token-budget
context management with non-destructive compaction, and extends agent runs with MCP
servers, automation, web search, memory, local models, and remote shell tools.

## Screenshots

<table>
<tr>
<td width="33%"><img src="assets/screenshot_1.jpg" alt="Chat" width="100%"/></td>
<td width="33%"><img src="assets/screenshot_2.jpg" alt="Tools" width="100%"/></td>
<td width="33%"><img src="assets/screenshot_3.jpg" alt="Settings" width="100%"/></td>
</tr>
</table>

## Features

- **Ten built-in provider types:** OpenAI, Anthropic, Google Gemini, DeepSeek,
  Qwen/DashScope, OpenRouter, OpenCode Go, Groq, Ollama, and local llama.cpp.
  Custom endpoints support OpenAI-compatible, Google, or Anthropic protocols.
- **Tree-structured conversations:** edit or regenerate earlier messages without
  discarding alternative branches.
- **Token-budget context:** 4K–1M estimated-token budgets and non-destructive
  Compact capsules that retain a verbatim recent suffix.
- **Agentic tools:** web search, memory, past-conversation RAG, image generation,
  MCP servers, Tasks/Loops, remote shell/files, durable Conch jobs, and an Alpine
  Linux sandbox.
- **Local intelligence:** GGUF chat models and local embeddings through llama.cpp —
  fully offline capable.
- **Portable data:** versioned `.agentx` ZIP archives (legacy `.agora` files still
  import), ChatGPT/Claude imports, and scheduled automatic backups.
- **Customizable UI:** Material 3 themes, fonts, haptics, thinking/tool presentation,
  and 10 interface languages plus system default.

### What AgentX adds on top of upstream

- **Universal math/physics/chemistry rendering:** mhchem, siunitx, braket,
  derivatives and 60+ more macros normalized to native math (65/65 coverage gate);
  single-`$` inline math on by default; raw LaTeX never shown.
- **Calibrated decisions:** API key + custom base URL, Choice/Score/Noul answers with
  calibrated probabilities driving search re-rank, context pruning, and guardrails.
- **TinyFish + DuckDuckGo fusion search** (user key) plus a fast `browse_page` tool.
- **Chat / Plan / Build modes** with an in-composer switch; plan mode is read-only
  by construction.
- **Multi-model ensemble** (`ask_models`, up to 5 models, primary synthesizes) and
  **multi-key rotation** (round-robin + auto-failover per retry).
- **Artifacts:** Markdown/PDF reports with real typography, tables, and verified
  images, saved into a user-picked workspace folder.
- **Agent environment variables** (encrypted) exported into every shell command;
  `list_env` for discovery.
- **Persistent diagnostics:** an always-on, machine-parseable `session.log`
  recording app activity, shareable from Settings → Agent for debugging.
- **For AI agents:** `AGENTS.md` entry point, `docs/MAP.md` repo map,
  `docs/LESSONS.md` hard-won rules, `docs/MEMORY.md` full project memory.

Conch application-layer encryption is enabled when an API key is configured. A
blank-key Conch endpoint sends plain JSON and should use HTTPS. External providers
and tools receive only the data needed for the feature you invoke; see the privacy
documentation for the full boundary.

## Documentation

- 📖 **[User Manual](https://gogibeta.github.io/AgentX/)** — setup, providers,
  Context Compact, MCP, automation, tools, privacy, and data management.
- 🏗️ **[Architecture Guide](ARCHITECTURE.md)** — runtime, persistence, providers,
  tools, and data flows.
- 🧰 **[Development docs](development/documentation-maintenance.md)** — internal
  contracts, baselines, and documentation-maintenance policy.

Public manuals live under `docs/<locale>/`. Internal engineering documents live
under `development/`.

## Getting started

1. Install AgentX and open **Settings** from the conversation drawer.
2. Add credentials under **Providers**.
3. Sync and enable models under **Models**.
4. Pick a model from the chat bottom bar and send a message.

See the [Getting Started manual](https://gogibeta.github.io/AgentX/getting-started/).

### Build from source

Requirements: JDK 21, Android SDK 36, Android NDK (for the llama.cpp target),
and the PRoot prebuilts in `app/src/main/jniLibs/arm64-v8a/` (gitignored —
recoverable from a release APK; `build-proot.sh` is Linux-only).

```bash
# Unit tests (F-Droid flavor)
./gradlew :app:testFdroidDebugUnitTest

# Debug APK
./gradlew :app:assembleFdroidDebug

# Release APK (requires the signing key — see below)
./gradlew :app:assembleFdroidRelease
```

Release builds **fail closed** if the signing key is not configured — they never
silently fall back to an ephemeral key. Configure it via `local.properties`:

```properties
storeFile=/absolute/path/to/agentx-release-key.keystore
storePassword=<store password>
keyAlias=<key alias>
keyPassword=<key password>
```

In CI the key is restored from the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, and `KEY_PASSWORD` repository secrets.

The same keystore must be used for every release, forever: Android rejects
updates signed with a different key, which would force users to uninstall and
lose their API keys, conversations, and settings. Never rotate it — back it up.

## Tech stack

Kotlin, Jetpack Compose Material 3, Coroutines/Flow, Room, DataStore,
OkHttp/SSE, `kotlinx.serialization`, Android NDK/CMake, llama.cpp, Coil, and
Markdown/LaTeX rendering.

## Privacy

AgentX does not relay chat completions or run general analytics. Conversations
remain in app-managed local storage, while configured providers and tools are
contacted directly when used. Optional update checks and explicitly submitted
ratings have documented network destinations. After a crash, one report is kept
locally and sent only if the user confirms on the next launch; it contains
diagnostics but no conversation text or credentials. Secret settings normally use
an Android Keystore AES-GCM envelope, but legacy values and a deliberate
encryption-failure fallback can remain plaintext in DataStore; exported secrets
are also unencrypted inside a selected `.agentx` archive.

Read [Privacy & Security](https://gogibeta.github.io/AgentX/privacy/) and the
repository [Privacy Policy](PRIVACY.md).

## Notice: unauthorized third-party redistribution

A third-party Android app named "Jinlong AI-Pro" (package `com.youlong.ai`),
distributed as a component of the Android toolbox "Youlong Toolbox 9.0" and first
published on 2026-09-19, is built from this project's code and assets under a
different name and package. It is signed with a third-party certificate, still
calls this project's `newoether.space` endpoints, and adds capabilities the
original does not have (including an accessibility service that can read the
screen). It is not authorized, not affiliated, and not endorsed in any way.

The full technical analysis — sample hashes, signature details, identifiers found
in that build, and reproduction method — is in
[INCIDENT-2026-09-jinlong-ai-pro.md](INCIDENT-2026-09-jinlong-ai-pro.md).
As a consequence of this incident, **v2.2.0 and later are released under
GPL-3.0** ([LICENSE](LICENSE)); v2.1.0 and earlier remain MIT (see the
[historical license](https://github.com/newo-ether/Agora/blob/9fc92fc3518c880158111ae1e9534ed8ffd09c6d/LICENSE)).

## Contributing and license

Contributions are welcome through issues and pull requests. By submitting a pull
request you agree that your contribution is licensed under the license in effect
when it is merged.

**Current license: [GNU General Public License v3.0](LICENSE).** This applies to
AgentX v2.2.0 and later.

**Historical releases:** v2.1.0 and earlier remain available under their
[original MIT License](https://github.com/newo-ether/Agora/blob/9fc92fc3518c880158111ae1e9534ed8ffd09c6d/LICENSE).
These licenses apply to different versions; the current project is not offered
under a choice of MIT or GPL.

**Upstream credit:** AgentX builds on [Agora](https://github.com/newo-ether/Agora)
by newoether (MIT, v2.1.0 and earlier). For GPL releases, redistributors must
keep the copyright and license notices, state that they changed the files, and
make the complete corresponding source available under the same license. The
AgentX name, logo, and screenshots are not covered by the code license, and
modified builds must not imply that they are official or endorsed.
