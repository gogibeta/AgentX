# Decision-Model Skill — Jev / Drex Typed Decisions for AgentX

Settings → Jev picks the **decision provider**: **Jev** (TypeSafe System One)
or **Drex** (Nace AI, wire-compatible — same `POST /v1/systemone`, same typed
questions: **Noul** (boolean + probability), **Choice** (winner +
confidence), **Score** (rubric level + confidence)). Everything below works
identically on both; the app routes to the selected provider automatically.

How to know which is active: `prune_context` and decision diagnostics report
`decision_provider` (`jev` or `drex`) and the model name. Drex models are
user-selectable in Settings → Jev: `drex-v1.5` (default), `drex-v1.0`,
`drex-latest` (moving alias, currently v1.5).

Provider differences that matter to you:

| | Jev (TypeSafe) | Drex (Nace AI) |
|---|---|---|
| Endpoint | `https://api.typesafe.ai` | `https://drex.nace.ai` |
| State context window | ~32k tokens | **131,072 tokens** (drex-v1.5) |
| State budget per doc (app) | 1,500 chars | 6,000 chars |
| Call timeout (app) | 10s | 60s (model may take ~55s under load) |
| Keys | up to 20, any format | `nace_sk_…`, max 3 per account |
| Out-of-credit | — | HTTP 402 `insufficient_credit` (not billed; top up in Drex dashboard) |
| Validation errors | — | HTTP 422 with `error.issues[]` listing exact `path: message` — fix the body, never retry |

Fail-open: null/unavailable = no signal, fall back to non-Jev behavior.
`jev_unavailable` means not configured (Settings → Jev) — don't retry, tell
the user. Never retry 401/402/422; retry 429/529/5xx with backoff (honor
`retry-after-ms`).

## Search backends (for re-ranking context)

Web search runs on the provider chosen in Settings → Web Search:
**DuckDuckGo** (scraper), **Monid TinyFish** (API), or **Fusion**
(DuckDuckGo + TinyFish). Jev/Drex re-ranking (`relevanceScores`) applies to
the fused hits regardless of backend — it does not care which search way
produced them. If results look thin, check which backend is selected before
blaming the decision model.

## 1. Context pruning (`prune_context`)

Jev scores items by keep-probability; you set the threshold. Scores cluster in
a cliff (~0.40–0.85 by workload) — there is no clean keep/drop line.

| Workload | `min_keep_probability` |
|---|---|
| Live working context (site content, logs, prefs still in use) | **0.3** |
| Redundant branches / dead ends | 0.5 (default) |
| Aggressive cleanup of old tool noise | 0.6+ (expect collateral) |

Rules: pruning is irreversible for model-visible context (Room history is
untouched). Never prune preemptively — prune actual noise. If scores cluster
just under your threshold, lower the threshold, don't accept a wipeout. The
auto-compact (Settings → Agent) handles routine hygiene; prefer it.

## 2. Two-decision compaction (fast-jev-compaction pattern)

Never summarize to compact — summaries lose paths, errors, constraints. For
each tool call+result pair, ask TWO Noul questions in ONE request:

- `keep_call`: "knowing this call was made, with its input, still matters?"
- `keep_result`: "the result's contents are still needed, and re-running the
  tool would not do?"

Three-way decision per pair at threshold T (default 0.5):

- `keep_result ≥ T` → keep call AND result verbatim
- else `keep_call ≥ T` → keep call, truncate result to first 300 chars +
  `[…truncated…]`
- else → drop call + result together

Rules: pin the newest round (never compact what you're about to reason
about). Never orphan a result without its call. User/assistant text is never a
candidate — only tool calls/results. Fit state by truncating tool inputs in
the STATE Jev sees (1000→200→60 chars), never in what you keep. If reduction
< 25%, skip — not worth the API cost.

## 3. Ultrafast browser loop (browser-use/jev-ultrafast pattern)

Per browser step: ONE Jev request = Choice over operations
(`click/type/select/scroll/wait/done/blocked`) + speculative target heads
(`click_target`, `type_target`, …) sharing the same observed element table.
Two decisions, one round trip. Call an LLM ONLY when the operation is
`type` (to generate the text) — never to choose actions. No screenshots in
the loop: Jev consumes structured element tables (`[1] button "Search"`);
screenshots are for user thumbnails only. After selection, validate the
target (freshness, occlusion) — reject covered controls before acting.
`done` requires independent outcome verification (did the goal actually
happen?), never trust the choice alone.

## 4. Skill routing (jev-agent-skill-router pattern)

When unsure which skill fits a request: one Jev Choice over candidate skill
names + descriptions. Outcomes: `route` / `no_skill` / `review`. Rules: if the
catalogue is large, batch it (Choice per batch, keep top-2 per batch, final
Choice over the shortlist). Confidence < 0.5 → `no_skill` or `review` —
weak matches are declined, never guessed. Use for the agent's own skill
selection; explicit user intent always wins over routing.

## 5. Effort routing (spending-effort-with-jev pattern)

Before starting work: one Choice over effort (`low/medium/high/max`) + one
Noul "is this spec fuzzy?". Change effort/model tier only at confidence ≥
0.7; below that, keep the current plan. Use to decide: quick answer vs.
subagent delegation vs. full multi-model verification.

## 6. Tool-call guard (Reflex / jev-axi / pi-verdict pattern)

Before risky tool calls: deterministic rules FIRST (blocklist always wins),
then one Jev Choice (`allow/ask/deny`) for the gray zone only. Never send
obviously-safe commands to Jev. `deny` blocks, `ask` escalates to the user,
errors/timeouts deny. Jev never overrides a deterministic deny.

## 7. Secret guard (jev-secret-guard pattern)

Block known secret formats locally (regex). For unknown high-entropy strings,
send to Jev MASKED ONLY — never the raw value — with a Noul "is this a real
credential?". Block at ≥ 0.80. Raw secrets must never enter Jev state.

## 8. Log triage (jev-logtriage pattern)

Batch collapsed log lines into one Jev request (Noul/Score/Choice per line),
map answers in code to suppress/watch/review/notify/page. Low confidence →
review, never auto-act. Use when diagnosing from large log dumps.

## Global rules

- Thresholds belong to the caller, written where the decision is made.
- Jev unavailable → fall back, don't stall. A Jev decision is never
  permission to bypass user approval.
- Probabilities are not proof: high keep-probability ≠ safe to delete;
  Choice confidence measures distribution concentration, not correctness.
- Keep decision state small: truncate in the STATE, never in what you keep.
  On Drex you get ~4x the headroom (131k vs 32k tokens) — prefer Drex for
  pruning/re-ranking large tool dumps; keep Jev for quick small decisions.
