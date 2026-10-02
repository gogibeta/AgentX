# Jev Skill — Typed Decisions for AgentX

Jev = TypeSafe System One (`POST /v1/systemone`): typed questions — **Noul**
(boolean + probability), **Choice** (winner + confidence), **Score** (rubric
level + confidence) — over unstructured state. Fail-open: null/unavailable =
no signal, fall back to non-Jev behavior. `jev_unavailable` means not
configured (Settings → Jev) — don't retry, tell the user.

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
- Keep Jev state small: truncate in the STATE, never in what you keep.
