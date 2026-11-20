# autoDate brain (proxy)

The server the phone asks "like or skip?". It holds your preferences and the prompts, checks every message, enforces a daily cap, and logs every call so you can see what it costs. It runs two ways, with the same endpoints:

| | **On your PC** (`npm run local`) | **On Cloudflare** (`npm run deploy`) |
|---|---|---|
| Model access | Claude Code with your Claude subscription (default), or an API key | Anthropic API key |
| You pay | $0 extra on the subscription (it uses your plan's limits) | Per token |
| PC must be on | Yes | No |
| Cost log | `data/ledger.jsonl`, `npm run costs` | KV, `GET /v1/costs` |

Your own preferences, facts and voice samples live in `config/`, which git ignores. The first `npm run local`, `dev` or `deploy` copies the templates from `config.example/` into it. Replace every `TEMPLATE` line before you use the app: the AI only knows what's in these files.

To change your preferences or the prompts, edit them and restart (or redeploy). You don't need to rebuild the app.

| File | What to edit |
|---|---|
| `config/preferences.json` | What you're looking for, your never-list, like thresholds. `city` is where suggested dates happen; `matches` is `women`, `men` or `people` and sets the pronouns in the prompts |
| `config/facts.json` | True facts about you that messages may mention. Nothing else can be claimed |
| `config/voice_samples.md` | 10–20 real messages you've sent, so openers sound like you |
| `src/prompts.ts` | The judge and opener prompts |
| `config/prices.json` | Prices per model, used for the cost log and "what if it ran on X" comparisons (Claude, GLM, add any) |
| `wrangler.toml` | Cloudflare: `MAX_DAILY_USD`, models, effort |
| `.env.local` | PC: `BACKEND`, `PORT`, `MAX_DAILY_USD`, `JUDGE_MODEL`, `PREFILTER_MODEL`, `EFFORT`, `ANTHROPIC_API_KEY`, `OPENROUTER_API_KEY`, `DEVICE_TOKEN` (made on first run) |

## Run on your PC (Claude subscription)

```sh
cd proxy
npm install
npm run local            # BACKEND=claude-code by default; prints the device token
```

It runs each request through `claude -p` (Claude Code, logged in as you) with tools, MCP servers, settings and CLAUDE.md turned off. An `ANTHROPIC_API_KEY` in your shell is ignored in this mode, so you're never billed by surprise. Use `BACKEND=api npm run local` to use an API key from your PC instead.

Connect the phone:
- **USB:** `adb reverse tcp:8787 tcp:8787`, then the brain URL in the app is `http://localhost:8787`.
- **Wi-Fi:** `http://<your PC's LAN IP>:8787`. Allow port 8787 through Windows Firewall. WSL must be in mirrored networking mode, which yours is.
- **Away from home:** install Tailscale on the PC and phone and use the PC's Tailscale IP.

## Run on your own server (Docker, always on)

`Dockerfile` runs `src/local.ts` with Claude Code inside, so an always-on box can use a Claude subscription the same way your PC does. Copy `proxy/` over (including your `config/`, which git doesn't track), then build and run it with port 8787 published, a volume on `/app/data` for the ledger and one on `/home/node/.claude` for the Claude login. Put the settings from `.env.local` in the container's environment. Sign in once with `docker exec -it <container> claude auth login`, or set `BACKEND=api` and `ANTHROPIC_API_KEY`, or `BACKEND=openrouter` and `OPENROUTER_API_KEY`.

From `proxy/`, this copies the code and your config in one go:

```sh
{ git ls-files -co --exclude-standard .; ls config/*; } | sort -u | tar -cf - -T - | ssh <you>@<server> 'tar -xf - -C <dir on the server>'
```

`.github/workflows/deploy-proxy.yml` can redeploy on every push to `main`. It runs only when the repo variable `PROXY_URL` is set, and needs the `DEPLOY_*` secrets listed at the top of the file. It never sends `config/`, so copy that by hand when you change it.

## What does it cost?

```sh
npm run costs        # last 30 days; `npm run costs 7` for a week
```

Shows what you actually paid, what the same calls cost at API list price, a per-month projection, what the same usage would cost on every model in `config/prices.json` (Opus, Sonnet, Haiku, GLM…), and on the subscription backend how your weekly limit moved during autoDate calls. The daily cap (`MAX_DAILY_USD`) counts list-price equivalent on both backends, so it also bounds how much of your subscription a day can use.

## Deploy to Cloudflare (API key, ~10 minutes)

```sh
cd proxy
npm install
npx wrangler login                       # free Cloudflare account
npx wrangler kv namespace create SPEND   # paste the printed id into wrangler.toml
npx wrangler secret put ANTHROPIC_API_KEY
npx wrangler secret put DEVICE_TOKEN     # any long random string, e.g. `openssl rand -hex 24`
npx wrangler deploy                      # prints https://autodate-proxy.<you>.workers.dev
```

Paste the URL and the device token into the autoDate app. The setup guide asks for them on its Connect step, and Settings → Brain has them too.

Check it:
```sh
curl -H "authorization: Bearer <DEVICE_TOKEN>" https://autodate-proxy.<you>.workers.dev/v1/spend
```

## Endpoints

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/v1/spend` | | `backend`, `spend_today` (paid), `list_today` (API-price equivalent), `max_daily_usd` (cap in effect), `default_max_daily_usd`, `max_daily_usd_ceiling`, `thresholds` |
| POST | `/v1/budget` | `{max_daily_usd}` | Sets the daily cap from the phone (Settings → Brain), up to $50. Works even after today's cap is reached |
| GET | `/v1/costs` | | 30-day report: totals, per-month projection, what-if per model, subscription weekly-limit movement |
| POST | `/v1/judge` | `{profile: {text, images[base64 jpeg], likeables[]}, examples[], prefilter}` | decision, score, reasons, like target, comment, flags, cost |
| POST | `/v1/opener` | `{profile, chat[], recent_openers[]}` | `opener` (or null + `problem`), cost |

Every request needs `Authorization: Bearer <DEVICE_TOKEN>`. After the daily cap of list-price-equivalent usage it answers 429 and the app stops the session. The cap is `MAX_DAILY_USD` unless one was set from the phone, which is stored in KV (Worker) or `data/cap.json` (PC) and wins until changed again.

Every message (like-comments and openers) passes `src/validate.ts` before it's returned: length limit, at most one emoji, no links, phone numbers or handles, a blocklist, and no near-repeats of recent openers.
