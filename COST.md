# Cost Breakdown

All figures are in USD and are estimates as of Oct 2026. Items marked **(verify)** change often or vary by region.

Running on your own phone removes the biggest infrastructure cost. What's left is Claude, Hinge, and a free-tier proxy.

## 1. LLM (Claude API)

### Rates

| Model                                   | Input / 1M tok | Output / 1M tok | Cache read / 1M |
| --------------------------------------- | -------------- | --------------- | --------------- |
| Claude Opus 5.5 (`claude-opus-5-5`)     | $4.00          | $20.00          | $0.20           |
| Claude Sonnet 5.5 (`claude-sonnet-5-5`) | $2.00          | $10.00          | $0.20           |
| Claude Haiku 5.5 (`claude-haiku-5-5`)   | $0.10          | $0.50           | —               |

Images cost about `width × height / 750` tokens. The app downscales screenshots to 540×1200, which is about 860 tokens each.

### Per-profile (judge call)

| Part                               | Tokens | Billed as  |
| ---------------------------------- | ------ | ---------- |
| System + preferences + few-shot    | ~5,000 | cache read |
| 4 screenshots @ 540×1200           | ~3,500 | input      |
| Profile text                       | ~500   | input      |
| Output (thinking + JSON + comment) | ~600   | output     |

| Model                                 | Cost / profile |
| ------------------------------------- | -------------- |
| Opus 5.5                              | ≈ **$0.029**   |
| Sonnet 5.5                            | ≈ **$0.015**   |
| Haiku 5.5 pre-filter (1 photo + text) | ≈ **$0.0003**  |

Post-match opener: about $0.03 each on Opus, a few per week.

### Monthly by volume

| Scenario                 | Profiles/day | Opus only | Haiku → Opus (≈50% pass) | Sonnet only |
| ------------------------ | ------------ | --------- | ------------------------ | ----------- |
| Free Hinge (8 likes/day) | 40           | ~$35      | **~$18**                 | ~$18        |
| Hinge+ moderate          | 100          | ~$87      | **~$45**                 | ~$45        |
| Hinge+ heavy             | 200          | ~$175     | **~$90**                 | ~$90        |

- Assumes `effort: low`. At `medium`, expect 1.5–2× the cost.
- The Batch API discount doesn't apply, because every decision must happen in real time.
- **Shadow mode still costs LLM money** (it judges profiles while you swipe), but it's usually a few hundred profiles total, so about $5–10.
- Eval runs on your laptop: about $3–10 per run of 100–300 profiles.

### Or: your Claude subscription

The brain can run on your PC through Claude Code (`npm run local`), billed to your existing Claude plan instead of per token. That's $0 extra. It uses your plan's usage limits, and your PC has to be on during sessions. `npm run costs` logs real token counts either way, so you can compare what you'd pay on the API, on Sonnet/Haiku, or on GLM (rates in `proxy/config/prices.json`) with actual usage instead of these estimates.

## 2. Infrastructure

| Item                                                   | Monthly | Notes                                                   |
| ------------------------------------------------------ | ------- | ------------------------------------------------------- |
| Your Android phone                                     | $0      | Already owned. Needs Android 11+                        |
| Cloudflare Worker proxy + KV                           | $0      | Free tier (100k requests/day) is far more than enough   |
| Electricity (screen on while charging, ~30–60 min/day) | ~$0.10  |                                                         |
| Android dev tools                                      | $0      | Android Studio. No Play Store account needed (sideload) |

## 3. Hinge subscription

| Tier   | Approx. monthly (US, 1-month plan)           | What it gives the agent                                   |
| ------ | -------------------------------------------- | --------------------------------------------------------- |
| Free   | $0                                           | ~8 likes/day (verify)                                     |
| Hinge+ | ~$30–50 (verify; cheaper on 3/6-month plans) | Unlimited likes, dealbreaker filters (free pre-filtering) |
| HingeX | ~$50–80 (verify)                             | Not needed                                                |

## 4. Totals

| Setup                                                      | LLM     | Hinge   | **Monthly**   |
| ---------------------------------------------------------- | ------- | ------- | ------------- |
| **Build & test** (shadow + approve, free Hinge)            | ~$10–20 | $0      | **~$10–20**   |
| **Light auto** (free Hinge, 40 profiles/day, Haiku → Opus) | ~$18    | $0      | **~$18**      |
| **Recommended** (Hinge+, 100 profiles/day, Haiku → Opus)   | ~$45    | ~$30–50 | **~$75–95**   |
| **Heavy** (Hinge+, 200/day, Opus only)                     | ~$175   | ~$30–50 | **~$205–225** |

One-time cost: $0.

## 5. Cost controls

- `max_daily_usd` enforced in the **proxy** (default $3/day). The app can't overspend even if it has a bug.
- Daily caps on profiles judged and likes sent.
- Spend shown live in the status bubble and in the end-of-session notification.
- Downscaled screenshots, cached prompt prefix, `effort: low` by default.
- Haiku pre-filter can be toggled. Check its rejects weekly so it isn't dropping good profiles.
