import { randomBytes } from "node:crypto";
import { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { AnthropicBackend } from "./backends/anthropic";
import { ClaudeCodeBackend } from "./backends/claudeCode";
import { OpenRouterBackend } from "./backends/openrouter";
import { type Backend, Brain } from "./brain";
import { handle } from "./handler";
import { addEntry, type DayTotal, emptyDay, type Entry, type Ledger } from "./ledger";
import { priceTable } from "./pricing";
import { makePrompts } from "./prompts";

/**
 * The brain server on your own computer. BACKEND=claude-code (default) runs every request through
 * Claude Code with your Claude subscription; BACKEND=api uses ANTHROPIC_API_KEY; BACKEND=openrouter uses OPENROUTER_API_KEY. Every call is
 * appended to data/ledger.jsonl so `npm run costs` can show what it cost or would have cost.
 */
const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const envFile = join(root, ".env.local");

const SETTINGS = ["BACKEND", "PORT", "DEVICE_TOKEN", "ANTHROPIC_API_KEY", "OPENROUTER_API_KEY", "MAX_DAILY_USD", "JUDGE_MODEL", "PREFILTER_MODEL", "EFFORT"];

/** Settings from proxy/.env.local, overridden by the same names in the shell environment. */
export function loadEnv(): Record<string, string> {
  const vars: Record<string, string> = {};
  if (existsSync(envFile)) {
    for (const line of readFileSync(envFile, "utf8").split("\n")) {
      const m = line.match(/^\s*([A-Z_]+)\s*=\s*(.*?)\s*$/);
      if (m) vars[m[1]] = m[2];
    }
  }
  for (const k of SETTINGS) if (process.env[k]) vars[k] = process.env[k]!;
  return vars;
}

export function loadConfig() {
  const read = (f: string) => readFileSync(join(root, "config", f), "utf8");
  const pricesRaw = JSON.parse(read("prices.json"));
  return {
    prompts: makePrompts({
      preferences: JSON.parse(read("preferences.json")),
      facts: JSON.parse(read("facts.json")),
      voiceSamples: read("voice_samples.md"),
      prices: pricesRaw,
    }),
    prices: priceTable(pricesRaw),
  };
}

export const localDay = (offset = 0) => {
  const d = new Date(Date.now() - offset * 86_400_000);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
};

/** Append-only JSONL file; totals are recomputed on read (it stays small). */
export class FileLedger implements Ledger {
  constructor(private file: string) {
    mkdirSync(dirname(file), { recursive: true });
  }

  async record(e: Entry) {
    appendFileSync(this.file, JSON.stringify(e) + "\n");
  }

  async listToday() {
    return (await this.days(1))[0].listUsd;
  }

  async days(n: number): Promise<DayTotal[]> {
    const wanted = Array.from({ length: n }, (_, i) => localDay(i));
    const totals = new Map(wanted.map((d) => [d, emptyDay(d)]));
    if (existsSync(this.file)) {
      for (const line of readFileSync(this.file, "utf8").split("\n")) {
        if (!line.trim()) continue;
        const e = JSON.parse(line) as Entry;
        const t = totals.get(e.day);
        if (t) addEntry(t, e);
      }
    }
    return wanted.map((d) => totals.get(d)!);
  }
}

export const ledgerFile = join(root, "data", "ledger.jsonl");
/** Daily cap set from the phone; overrides MAX_DAILY_USD until changed again. */
const capFile = join(root, "data", "cap.json");

function main() {
  const env = loadEnv();
  if (!env.DEVICE_TOKEN) {
    env.DEVICE_TOKEN = randomBytes(24).toString("hex");
    appendFileSync(envFile, `DEVICE_TOKEN=${env.DEVICE_TOKEN}\n`);
  }
  const backendName = env.BACKEND ?? "claude-code";
  const effort = (env.EFFORT ?? "low") as "low" | "medium" | "high";
  let backend: Backend;
  if (backendName === "api") {
    if (!env.ANTHROPIC_API_KEY) throw new Error("BACKEND=api needs ANTHROPIC_API_KEY in proxy/.env.local");
    backend = new AnthropicBackend(env.ANTHROPIC_API_KEY, effort);
  } else if (backendName === "openrouter") {
    if (!env.OPENROUTER_API_KEY) throw new Error("BACKEND=openrouter needs OPENROUTER_API_KEY in proxy/.env.local");
    backend = new OpenRouterBackend(env.OPENROUTER_API_KEY, effort);
  } else {
    backend = new ClaudeCodeBackend(effort);
  }
  const { prompts, prices } = loadConfig();
  const ledger = new FileLedger(ledgerFile);
  const models = { judge: env.JUDGE_MODEL ?? "claude-opus-5-5", prefilter: env.PREFILTER_MODEL ?? "claude-haiku-5-5" };
  const brain = new Brain(backend, ledger, prompts, prices, models, () => localDay());
  const port = Number(env.PORT ?? 8787);
  const deps = {
    brain, ledger, prompts, prices, deviceToken: env.DEVICE_TOKEN, maxDailyUsd: Number(env.MAX_DAILY_USD ?? 5), backend: backend.name,
    capStore: {
      get: async () => (existsSync(capFile) ? (JSON.parse(readFileSync(capFile, "utf8")) as { usd: number }).usd : null),
      set: async (usd: number) => {
        mkdirSync(dirname(capFile), { recursive: true });
        writeFileSync(capFile, JSON.stringify({ usd }));
      },
    },
  };

  createServer(async (req, res) => {
    const chunks: Buffer[] = [];
    for await (const c of req) chunks.push(c as Buffer);
    const request = new Request(`http://localhost${req.url}`, {
      method: req.method,
      headers: req.headers as Record<string, string>,
      body: req.method === "GET" || req.method === "HEAD" ? undefined : Buffer.concat(chunks),
    });
    const started = Date.now();
    const response = await handle(request, deps);
    const text = await response.text();
    res.writeHead(response.status, { "content-type": "application/json" });
    res.end(text);
    console.log(`${new Date().toLocaleTimeString()} ${req.method} ${req.url} → ${response.status} (${Date.now() - started} ms)`);
  }).listen(port, "0.0.0.0", () => {
    console.log(`autoDate brain on http://0.0.0.0:${port}  backend=${backend.name}  judge=${models.judge}  prefilter=${models.prefilter}`);
    console.log(`Device token: ${env.DEVICE_TOKEN}`);
    console.log(`Daily cap (list-price equivalent): $${deps.maxDailyUsd}`);
  });
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main();
