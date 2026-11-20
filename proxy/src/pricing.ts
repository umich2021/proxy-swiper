/** Token counts for one call, normalized across backends. */
export type Usage = { input: number; output: number; cacheRead: number; cacheWrite: number };

/** $ per 1M tokens: [input, output, cache read, cache write]. */
export type PriceTable = Record<string, [number, number, number, number]>;

export function priceTable(raw: Record<string, unknown>): PriceTable {
  const out: PriceTable = {};
  for (const [k, v] of Object.entries(raw)) {
    if (Array.isArray(v) && v.length === 4) out[k] = v as [number, number, number, number];
  }
  return out;
}

export function costOf(prices: PriceTable, model: string, u: Usage): number {
  const p = prices[model] ?? prices["claude-opus-5-5"];
  if (!p) return 0;
  return (u.input * p[0] + u.output * p[1] + u.cacheRead * p[2] + u.cacheWrite * p[3]) / 1_000_000;
}

export function addUsage(a: Usage, b: Usage): Usage {
  return { input: a.input + b.input, output: a.output + b.output, cacheRead: a.cacheRead + b.cacheRead, cacheWrite: a.cacheWrite + b.cacheWrite };
}

export const ZERO: Usage = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 };
