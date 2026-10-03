# Social Skill — AgentX Social Tools

Use this skill before any social-media task. The social tools read public posts
through **your own FxEmbed worker** — not through the app developer's servers.
The worker's contract is its `llms.txt` — re-read it when something surprises
you: `{worker-url}/llms.txt`. (2026-10-03 refresh: X search is relay-served
again, timelines take pagination params, thread/quotes/conversation/media/RSS
routes added.)

## Setup (do once, Settings → Social)

1. Deploy your own worker from the FxEmbed / twapiworker project.
2. Paste the worker URL in Settings → Social and tap **Validate**.
3. For **credential-gated X routes** (conversation, profile media/articles/
   followers/following/about, reposts, typeahead, trends) configure a
   credential pool on YOUR worker (`wrangler secret put`). Without it those
   routes 404/500 — that is a worker-config gap, not an app bug. Tell the
   user exactly which secret to set.

## The 9 tools (all read-only, no login needed)

- `social_resolve(url)` — ONE universal endpoint (`/ai/2/post?url=`) handles
  every network: X, Bluesky, TikTok, Instagram, Threads, any Mastodon
  instance. Paste any post or profile URL; you get Markdown back.
- `social_search(network, query, feed?, domain?)` — network truth table:
  - `x` — **relay-served** (RSS discovery + per-post hydration). All X
    operators pass through (`from:`, `filter:`, `lang:`, `since:`,
    `min_faves:`). `feed` = latest|top|media. 404 = relay found nothing
    (valid answer). People search stays removed (no relay surface).
  - `bluesky` — works (worker retries the public AppView on edge-403).
  - `threads` — proxy-only; 501 without an account pool on the worker.
  - `mastodon` — **people search only**. Status search returns `results: []`
    for anonymous callers BY DESIGN. Pass the instance domain.
  - `tiktok`, `instagram` — no search route exists. Say so honestly.
- `social_timeline(network, handle, count?, cursor?, with_replies?, since?, lang?, domain?)`
  — an account's recent posts. `count` 1-100. `cursor` = opaque token from
  the previous call's `Next:` line (pass back for the next page).
  `with_replies=true` includes replies. `since=` polls for new posts
  (204/nothing-new is valid). `lang=` translates inline (e.g. `es`).
- `social_thread(tweet_id, lang?)` — unroll an X author's whole thread in
  order. Numeric tweet id only.
- `social_quotes(tweet_id, count?, cursor?)` — who quoted a tweet and what
  they said. 404/upstream_empty when nothing quotes it (valid).
- `social_conversation(tweet_id, ranking_mode?, cursor?)` — post + ancestors
  + paginated direct replies. `ranking_mode` = likes|recency. NEEDS a
  credential pool (404 without it) — surface honestly.
- `social_profile_search(handle, query, feed?, count?, cursor?)` — search
  WITHIN one X account's posts (relay-served).
- `social_profile_media(handle, count?, cursor?)` — account's media tab.
  NEEDS a credential pool (500 without it) — surface honestly.
- `social_rss(handle)` — the account's RSS feed (`/twitter/{handle}/feed.xml`),
  the cheapest way to monitor for new posts.

## Recipes (from the worker's llms.txt cookbook)

- **What is this account about?** `social_timeline(network=x, handle, count=20,
  with_replies=true)` → summarize the posts.
- **Unroll + archive a thread:** `social_thread(tweet_id)`.
- **Both sides of a debate:** `social_conversation(tweet_id, ranking_mode=recency)`
  + `social_quotes(tweet_id)`.
- **Monitor an account:** `social_rss(handle)` or poll
  `social_timeline(..., since=...)`.
- **Keyword search + people map:** `social_search(network=x, query, feed)` →
  follow `cursor` for more pages (time-window pagination).
- **Deep Bluesky read:** `social_timeline(network=bluesky, handle, count=1)` →
  copy the rkey from the post URL → `social_resolve` the
  `bsky.app/profile/{handle}/post/{rkey}` URL.

## Rules

- **Never fabricate post IDs.** Resolve real URLs the user gives you, or
  search first and resolve what search returns.
- **404 envelope = the answer, not a bug.** "Upstream has nothing" (deleted /
  private / unknown / relay found nothing) — do not retry.
- **No retry loops, ever.** TikTok 429s flap as 404s; hammering makes the
  cooldown longer. One attempt, then report.
- **Mandatory User-Agent.** The app sends `AgentX/<version>` on every call;
  without it the worker 401s. If you call the worker yourself (shell/curl),
  set a descriptive UA — bare `Python-urllib` gets edge-403d.
- **Credential-gated X routes** (conversation, profile media/articles/
  followers/following/about, reposts, typeahead, trends) 404/500 on a worker
  with no credential pool. Report the gap; don't fake results.
- If the worker URL is not configured, the tools refuse with `not_configured`
  and point at Settings → Social. Don't work around it.
