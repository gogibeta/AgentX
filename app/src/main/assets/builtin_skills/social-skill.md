# Social Skill — AgentX Social Tools

Use this skill before any social-media task. The social tools read public posts
through **your own FxEmbed worker** — not through the app developer's servers.

## Setup (do once, Settings → Social)

1. Deploy your own worker from the FxEmbed / twapiworker project.
2. Paste the worker URL in Settings → Social and tap **Validate**.
   Validation expects HTTP 200 with any body on the worker's `/ai` realm —
   the realm answers Markdown, not JSON, and that is fine.
3. For **search** on X / Threads you must configure a credential pool on YOUR
   worker (`wrangler secret put`). Without it, search returns `upstream_empty`
   (X), HTTP 500 (Bluesky), or HTTP 501 (Threads) — those are worker-config
   gaps, not app bugs. Tell the user exactly which secret to set.

## The 2 tools

- `social_resolve(url)` — resolve one post/profile URL to text + stats + media.
  Works on X, Mastodon, Bluesky, Threads. TikTok needs a numeric video ID;
  Instagram is logged-out-only and may HTTP 500.
- `social_search(network, query)` — search. Supported: `x`, `bluesky`,
  `threads`, `mastodon` (mastodon needs the instance domain, e.g.
  `mastodon.social`). TikTok and Instagram have no search route — say so
  honestly instead of retrying.

## Rules

- **Never fabricate post IDs.** Two "failures" in testing were fake IDs I made
  up. Resolve real URLs the user gives you, or search first and resolve what
  search returns.
- **Mastodon silent empty results:** HTTP 200 with `results: []` across
  instances means the worker's search path is broken. Report it; don't loop.
- **One attempt per TikTok ID** — `upstream_empty` is the documented limit.
- **Media:** `social_resolve` extracts images/video from X posts when the
  worker provides them. For anything else, download via the page URL yourself.
- If the worker URL is not configured, the tools refuse with `not_configured`
  and point at Settings → Social. Don't work around it — ask the user to
  configure it.
