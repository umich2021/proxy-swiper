import { addUsage, costOf, type PriceTable, type Usage, ZERO } from "./pricing";

/** One model call, as recorded by either runtime. */
export type Entry = {
  ts: number;
  day: string; // YYYY-MM-DD
  endpoint: "judge" | "prefilter" | "opener" | "comment";
  backend: "api" | "openrouter" | "claude-code" | "codex" | "openai" | "xai" | "kimi";
  model: string;
  usage: Usage;
  /** What you were billed: API list price on the pay-per-token backends, 0 on the subscription backends (claude-code, codex). */
  actualUsd: number;
  /** What this call would cost at API list price for the model that ran it. */
  listUsd: number;
  durationMs: number;
  /** Max subscription utilization after the call (0-1), claude-code backend only. */
  limits?: { fiveHour?: number; sevenDay?: number };
};

/** Per-day totals, enough for spend caps, reports and what-if pricing. */
export type DayTotal = {
  day: string;
  calls: number;
  profiles: number;
  openers: number;
  actualUsd: number;
  listUsd: number;
  byModel: Record<string, Usage>;
  sevenDayMin?: number;
  sevenDayMax?: number;
};

export interface Ledger {
  record(e: Entry): Promise<void>;
  /** Today's list-price-equivalent total; the daily cap applies to this on every backend. */
  listToday(): Promise<number>;
  days(n: number): Promise<DayTotal[]>;
}

export function emptyDay(day: string): DayTotal {
  return { day, calls: 0, profiles: 0, openers: 0, actualUsd: 0, listUsd: 0, byModel: {} };
}

export function addEntry(t: DayTotal, e: Entry): DayTotal {
  t.calls++;
  if (e.endpoint === "judge") t.profiles++;
  if (e.endpoint === "opener") t.openers++;
  t.actualUsd += e.actualUsd;
  t.listUsd += e.listUsd;
  t.byModel[e.model] = addUsage(t.byModel[e.model] ?? ZERO, e.usage);
  const w = e.limits?.sevenDay;
  if (w !== undefined) {
    t.sevenDayMin = Math.min(t.sevenDayMin ?? w, w);
    t.sevenDayMax = Math.max(t.sevenDayMax ?? w, w);
  }
  return t;
}

export type Report = ReturnType<typeof buildReport>;

/**
 * Totals over the given days, projected to 30 days, plus what the same token usage would have cost
 * on every model in the price table.
 */
export function buildReport(days: DayTotal[], prices: PriceTable) {
  const active = days.filter((d) => d.calls > 0);
  const n = Math.max(active.length, 1);
  const perMonth = (x: number) => (x / n) * 30;
  const total = active.reduce(
    (a, d) => ({
      calls: a.calls + d.calls, profiles: a.profiles + d.profiles, openers: a.openers + d.openers,
      actualUsd: a.actualUsd + d.actualUsd, listUsd: a.listUsd + d.listUsd,
    }),
    { calls: 0, profiles: 0, openers: 0, actualUsd: 0, listUsd: 0 },
  );
  // Calls are attributed by role: whatever ran the judge/opener vs whatever ran the pre-filter
  // would both be re-priced as one model in a what-if, which is the simple question to ask.
  let usage = ZERO;
  for (const d of active) for (const u of Object.values(d.byModel)) usage = addUsage(usage, u);
  const whatIf = Object.keys(prices).map((model) => {
    const usd = costOf(prices, model, usage);
    return { model, usd, perMonthUsd: perMonth(usd), perProfileUsd: total.profiles ? usd / total.profiles : 0 };
  });
  const weekly = active
    .filter((d) => d.sevenDayMax !== undefined)
    .map((d) => ({ day: d.day, from: d.sevenDayMin!, to: d.sevenDayMax! }));
  return {
    daysWithActivity: active.length,
    total,
    perMonth: { actualUsd: perMonth(total.actualUsd), listUsd: perMonth(total.listUsd), profiles: perMonth(total.profiles) },
    whatIf,
    maxWeeklyLimit: weekly,
    days: active,
  };
}
