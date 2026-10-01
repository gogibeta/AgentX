# Social Skill — AgentX Social Tools

Use this skill before any social-media task. The social tools read public posts
through **your own FxEmbed worker** — not through the app developer's servers.
The worker's contract is its `llms.txt` — re-read it when something surprises
you: `{worker-url}/llms.txt`.

## Setup (do once, Settings → Social)

1. Deploy your own worker from the FxEmbed / twapiworker project.
2. Paste the worker URL in Settings → Social and tap **Validate**.
3. For **search** on Threads (and X lists/trends) configure a credential pool
   on YOUR worker (`wrangler secret put`). Without it those routes 501/500 —
   that is a worker-config gap, not an app bug. Tell the user exactly which
   secret to set.

## The 2 tools

- `social_resolve(url)` — ONE universal endpoint (`/ai/2/post?url=`) handles
  every network: X, Bluesky, TikTok, Instagram, Threads, any Mastodon
  instance. Paste any post or profile URL; you get Markdown back.
- `social_search(network, query)` — network truth table (worker llms.txt,
  2026-10-02):
  - `bluesky` — works.
  - `threads` — proxy-only; 501 without an account pool on the worker.
  - `mastodon` — **people search only**. Status search returns `results: []`
    for anonymous callers BY DESIGN (Mastodon restriction, not a bug).
    Pass the instance domain, e.g. `mastodon.social`.
  - `x` — **removed**. X keyword/people search is shut off upstream and the
    route was deleted from the worker. Do NOT retry — start from a known
    handle and read their profile timeline instead.
  - `tiktok`, `instagram` — no search route exists. Say so honestly.

## Rules

- **Never fabricate post IDs.** Resolve real URLs the user gives you, or
  search first and resolve what search returns.
- **404 envelope = the answer, not a bug.** "Upstream has nothing" (deleted /
  private / unknown) — do not retry.
- **No retry loops, ever.** TikTok 429s flap as 404s; hammering makes the
  cooldown longer. One attempt, then report.
- **Mandatory User-Agent.** The app sends `AgentX/<version>` on every call;
  without it the worker 401s. If you call the worker yourself (shell/curl),
  set a descriptive UA — bare `Python-urllib` gets edge-403d.
- **Credential-gated X routes** (profile media/articles/followers/following,
  reposts, typeahead, trends, conversation) 404/500 on a worker with no
  credential pool. Report the gap; don't fake results.
- If the worker URL is not configured, the tools refuse with `not_configured`
  and point at Settings → Social. Don't work around it.
