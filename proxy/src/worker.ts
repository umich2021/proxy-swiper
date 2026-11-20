import facts from "../config/facts.json";
import preferences from "../config/preferences.json";
import pricesRaw from "../config/prices.json";
import voiceSamples from "../config/voice_samples.md";
import { AnthropicBackend } from "./backends/anthropic";
import { Brain } from "./brain";
import { handle } from "./handler";
import { addEntry, type DayTotal, emptyDay, type Entry, type Ledger } from "./ledger";
import { priceTable } from "./pricing";
import { makePrompts } from "./prompts";

/** Cloudflare deployment: Anthropic API backend, daily totals in KV. */
interface Env {
  ANTHROPIC_API_KEY: string;
  DEVICE_TOKEN: string;
  MAX_DAILY_USD: string;
  JUDGE_MODEL: string;
  PREFILTER_MODEL: string;
  EFFORT: "low" | "medium" | "high";
  SPEND: KVNamespace;
}

const prompts = makePrompts({ preferences, facts, voiceSamples, prices: pricesRaw });
const prices = priceTable(pricesRaw);
const utcDay = (offset = 0) => new Date(Date.now() - offset * 86_400_000).toISOString().slice(0, 10);

/** One KV key per UTC day holding that day's totals. Single user, so read-modify-write is fine. */
class KvLedger implements Ledger {
  constructor(private kv: KVNamespace) {}

  private async get(day: string): Promise<DayTotal> {
    return ((await this.kv.get(`day:${day}`, "json")) as DayTotal | null) ?? emptyDay(day);
  }

  async record(e: Entry) {
    const t = addEntry(await this.get(e.day), e);
    await this.kv.put(`day:${e.day}`, JSON.stringify(t), { expirationTtl: 60 * 60 * 24 * 90 });
  }

  async listToday() {
    return (await this.get(utcDay())).listUsd;
  }

  async days(n: number) {
    return Promise.all(Array.from({ length: n }, (_, i) => this.get(utcDay(i))));
  }
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const ledger = new KvLedger(env.SPEND);
    const backend = new AnthropicBackend(env.ANTHROPIC_API_KEY, env.EFFORT);
    const brain = new Brain(backend, ledger, prompts, prices, { judge: env.JUDGE_MODEL, prefilter: env.PREFILTER_MODEL }, () => utcDay());
    return handle(req, {
      brain, ledger, prompts, prices, deviceToken: env.DEVICE_TOKEN, maxDailyUsd: Number(env.MAX_DAILY_USD || "3"), backend: "api",
      capStore: {
        get: async () => {
          const v = await env.SPEND.get("cap");
          return v == null ? null : Number(v);
        },
        set: (usd) => env.SPEND.put("cap", String(usd)),
      },
    });
  },
};
