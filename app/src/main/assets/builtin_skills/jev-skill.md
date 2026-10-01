# Jev Skill — Context Pruning with Nara

Use this skill before calling `prune_context`. Jev scores context items by
keep-probability; you set the threshold. The default is wrong for live work.

## The threshold cliff

Observed scores cluster in a narrow band (~0.40–0.85 depending on workload).
There is no threshold that keeps "useful" and drops "junk" cleanly — it is a
cliff, not a slope. The `prune_context` result now includes the raw per-item
scores: **read them** and calibrate instead of trusting the default.

| Workload | Recommended `min_keep_probability` |
|---|---|
| Live working context (site content, logs, prefs still in use) | **0.3** |
| Redundant branches / dead ends | 0.5 (default) |
| Aggressive cleanup of old tool noise | 0.6+ (expect collateral) |

The classic mistake: pruning live context at 0.5 and watching everything
score 0.40–0.44 get dropped. If the items are still live, 0.3 is correct.

## Rules

- **Pruning is irreversible for the conversation.** Dropped items leave the
  model-visible context permanently. Room history is untouched — the full
  history survives — but the working context does not get them back.
- **Never prune to "save context" preemptively.** Prune when the context is
  actually noisy: dead tool branches, superseded plans, resolved errors.
- **Check the scores first.** If everything clusters just under your
  threshold, lower the threshold instead of accepting a wipeout.
- **The visible auto-compact** (Settings → Agent → Auto-compact) is separate:
  it prunes old tool outputs automatically every N rounds, shows a snackbar
  each time, and can be turned off. It only touches model-visible context,
  never Room history. Prefer it over manual pruning for routine hygiene.
- **Jev only runs when enabled** (Settings → Jev) with a valid API key.
  `jev_unavailable` means it isn't configured — don't retry, tell the user.
