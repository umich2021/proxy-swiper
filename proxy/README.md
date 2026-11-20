# autoDate proxy

The small server the phone asks "like or skip?". It holds your AI access, your preferences, and the prompts, checks every message before it goes out, and stops for the day once you hit your spending cap.

## Run it

```sh
npm install
npm run local
```

It prints the device token and listens on port 8787. The first run copies `config.example/` into `config/` and writes `.env.local`. Settings go in `.env.local`, one `NAME=value` per line, and you restart after changing them.

## Pick your AI

Set `BACKEND` in `.env.local`. The photos get sent too, so with an API key pick a model that reads images.

| `BACKEND` | What you need | What you pay |
|---|---|---|
| `claude-code` (default) | A Claude subscription. `npm install -g @anthropic-ai/claude-code`, then `claude auth login` | Nothing extra, it uses your plan's limits |
| `codex` | A ChatGPT plan. `npm install -g @openai/codex`, then `codex login` | Nothing extra, it uses your plan's limits |
| `api` | `ANTHROPIC_API_KEY` | Per token |
| `openai` | `OPENAI_API_KEY`, plus `JUDGE_MODEL` and `PREFILTER_MODEL` | Per token |
| `xai` (Grok) | `XAI_API_KEY`, plus `JUDGE_MODEL` and `PREFILTER_MODEL` | Per token |
| `kimi` | `MOONSHOT_API_KEY`, plus `JUDGE_MODEL` and `PREFILTER_MODEL` | Per token |
| `openrouter` | `OPENROUTER_API_KEY`. Model names like `openai/...` or `x-ai/...` | Per token |

API keys add up fast, since every profile sends photos. The subscription backends are the cheap way to run this. With `codex`, leaving `JUDGE_MODEL` unset uses Codex's default model.

The Grok and Kimi apps have their own CLIs too, but neither documents a way to send photos from a script, so for those use the API key or OpenRouter. The `codex` backend follows OpenAI's docs for `codex exec` but hasn't been run against every plan. If it errors, the message shows Codex's own output.

Other settings:

| Setting | Default | What it does |
|---|---|---|
| `MAX_DAILY_USD` | `5` | Daily cap, counted at API prices on every backend. Also changeable from the app (Settings → Brain) |
| `JUDGE_MODEL` | `claude-opus-5-5` | The model that judges profiles and writes comments |
| `PREFILTER_MODEL` | `claude-haiku-5-5` | A cheaper model that skips obvious no's first |
| `EFFORT` | `low` | Reasoning effort, where the backend supports it |
| `PORT` | `8787` | |

Costs for non-Claude models are estimated at Claude Opus prices unless you add the model to `config/prices.json`. That keeps the daily cap on the safe side.

## Your config

| File | What goes in it |
|---|---|
| `config/preferences.json` | What you're looking for, hard no's, `thresholds` for how picky to be, `looks.min`, `city`, and `matches` (`women`, `men`, or `people`) |
| `config/facts.json` | True facts about you. Comments can only mention what's in here |
| `config/voice_samples.md` | 10 to 20 real messages you've sent, exactly as typed |
| `src/prompts.ts` | The prompts themselves, if you want to change how it judges or writes |

Git ignores `config/` (except `prices.json`), so your details stay on your machine.

## Connect the phone

- Same Wi-Fi: `http://<your computer's LAN IP>:8787`. On Windows, allow port 8787 through the firewall
- USB: `adb reverse tcp:8787 tcp:8787`, then `http://localhost:8787`
- Anywhere: Tailscale on both, then the computer's Tailscale IP

## Run it without your computer

**Cloudflare Worker** (Anthropic API key only):

```sh
npx wrangler login
npx wrangler kv namespace create SPEND   # paste the printed id into wrangler.toml
npx wrangler secret put ANTHROPIC_API_KEY
npx wrangler secret put DEVICE_TOKEN     # any long random string, e.g. `openssl rand -hex 24`
npm run deploy                           # prints https://autodate-proxy.<you>.workers.dev
```

**Your own server**: `Dockerfile` runs the same thing as `npm run local`, with Claude Code inside. Copy `proxy/` over, including your `config/`, publish port 8787, and mount volumes on `/app/data` (spend log) and `/home/node/.claude` (login). Put the `.env.local` settings in the container's environment, then sign in once with `docker exec -it <container> claude auth login`. `.github/workflows/deploy-proxy.yml` can redeploy over SSH on every push if you set the repo variable `PROXY_URL` and the secrets listed in that file.

## See what it costs

```sh
npm run costs        # last 30 days; `npm run costs 7` for a week
```

Every call is logged to `data/ledger.jsonl` with its token counts.
