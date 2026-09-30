# MAP.md — AgentX repo map (2-minute orientation)

Upstream Agora layout is unchanged; AgentX additions are marked **[AX]**.

```
app/src/main/java/com/newoether/agora/
├── api/
│   ├── openai/            # 7 OpenAI-protocol providers (incl. Custom = any router)
│   │   └── BaseOpenAiProvider.kt   # [AX] per-attempt key rotation lives here
│   ├── ApiKeyRotation.kt           # [AX] round-robin pick + failover order (pure)
│   ├── monid/MonidClient.kt        # [AX] Monid gateway: TinyFish /search + /fetch
│   └── typesafe/                   # [AX] TypeSafeClient (Jev wire), TypeSafeProvider
│       ├── JevDecisions.kt         # [AX] fail-open Choice/Score/Noul helpers
│       └── AnswerGuard.kt          # [AX] leak tripwire + synthesis cleaner
├── tool/                  # ToolProviders: definitions(ctx) + execute()
│   ├── ShellToolDefinitions.kt     # [AX] plan-mode filter, withAgentEnv, list_env
│   ├── ShellToolProvider.kt        # [AX] env export in BOTH dispatch paths
│   ├── WebSearchToolProvider.kt    # [AX] tinyfish/fusion branches, browse_page
│   ├── ArtifactToolProvider.kt     # [AX] save_artifact, fetch_image
│   ├── ArtifactExporter.kt         # [AX] MD writer + block-model PDF renderer
│   ├── EnsembleToolProvider.kt     # [AX] ask_models fan-out
│   └── CompactAssistToolProvider.kt# [AX] prune_context (Jev verbatim prune)
├── ui/
│   ├── chat/bottombar/ComposerModeChip.kt  # [AX] Chat/Plan/Build chip
│   ├── chat/message/MessageItemMarkdown.kt # [AX] auto-$ parsing
│   ├── components/
│   │   ├── LatexChemistry.kt  # [AX] \ce/mhchem → math
│   │   └── LatexPhysics.kt    # [AX] siunitx/braket/derivatives/operators
│   └── settings/SettingsAgentPage.kt       # [AX] mode/workspace/models/Jev/env/diag
├── viewmodel/
│   ├── GenerationContracts.kt      # [AX] agentMode/workspaceUri/models/env,
│   │                               #   typeSafe fields on GenerationContext
│   ├── GenerationRequestBuilder.kt # [AX] rotation pick, agentSnapshot (mock-safe)
│   ├── GenerationApiPathBuilder.kt # [AX] alternates → dispatch ProviderConfig
│   ├── ChatRuntime.kt              # [AX] EnsembleToolProvider wiring
│   └── ProviderRegistry.kt         # [AX] TypeSafe registration
├── data/
│   ├── SettingsAgentPreferenceStore.kt  # [AX] agent prefs slice (mode/models/env)
│   └── SettingsContracts.kt             # [AX] normalizeAgentMode, env crypto
└── util/
    ├── FileLog.kt          # [AX] persistent session.log
    └── DebugLog.kt         # [AX] forwards everything to FileLog

app/src/test/...            # mirrors main; gates: LatexCoverageProbe (65/65),
                            # ApiKeyRotationTest, AgentModesTest, MonidClientTest,
                            # TypeSafeClientTest, ArtifactExporter(Test|PdfTest)
docs/
├── MAP.md        # [AX] this file
├── MEMORY.md     # [AX] features, rig, credentials, commands, status
└── LESSONS.md    # [AX] append-only hard-won rules
AGENTS.md          # [AX] agent entry point (start here)
```

## Data flow (one send)
Chat UI → `ChatRuntime` → `GenerationRequestBuilder` (config+ctx: keys picked
round-robin, agent snapshot fail-closed) → `GenerationApiPathBuilder`
(dispatch `ProviderConfig` incl. `alternateApiKeys`) → `ProviderPassRunner` →
`BaseOpenAiProvider.generateResponse` (retries rotate keys) → tool loop
(`GenerationToolExecutor` → providers) → Room persistence.

## Where decisions get made (Jev layer)
`JevDecisions` (fail-open, never throws): fusion re-rank (`WebSearchToolProvider`),
`prune_context` scoring, future router/guardrail call sites. Thresholds always
live with the caller, never in the client.
