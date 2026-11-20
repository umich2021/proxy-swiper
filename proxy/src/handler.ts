import type { Brain, Profile } from "./brain";
import { buildReport, type Ledger } from "./ledger";
import type { PriceTable } from "./pricing";
import type { CommentEdit, Example, Prompts } from "./prompts";

export type Deps = {
  brain: Brain;
  ledger: Ledger;
  prompts: Prompts;
  prices: PriceTable;
  deviceToken: string;
  /** Default daily cap from the environment; the phone can override it through [capStore]. */
  maxDailyUsd: number;
  capStore: CapStore;
  backend: string;
};

/** Where a daily cap set from the phone is kept. Null means use the environment's default. */
export interface CapStore {
  get(): Promise<number | null>;
  set(usd: number): Promise<void>;
}

/** Highest cap the phone can set, so a typo like 300 can't run up a bill. */
const CAP_CEILING = 50;

class HttpError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json" } });
}

/** The phone's API, shared by the Cloudflare Worker and the local server. */
export async function handle(req: Request, d: Deps): Promise<Response> {
  try {
    if (req.headers.get("authorization") !== `Bearer ${d.deviceToken}`) throw new HttpError(401, "bad token");
    const path = new URL(req.url).pathname;
    const cap = (await d.capStore.get()) ?? d.maxDailyUsd;

    if (req.method === "GET" && path === "/v1/spend") {
      const today = (await d.ledger.days(1))[0];
      return json({
        backend: d.backend,
        spend_today: today?.actualUsd ?? 0,
        list_today: today?.listUsd ?? 0,
        max_daily_usd: cap,
        default_max_daily_usd: d.maxDailyUsd,
        max_daily_usd_ceiling: CAP_CEILING,
        thresholds: d.prompts.thresholds,
      });
    }
    if (req.method === "GET" && path === "/v1/costs") {
      return json(buildReport(await d.ledger.days(30), d.prices));
    }
    if (req.method !== "POST") throw new HttpError(404, "not found");

    // Before the budget check, so the cap can be raised after it's been reached.
    if (path === "/v1/budget") {
      const body = (await req.json()) as { max_daily_usd?: unknown };
      const usd = Number(body.max_daily_usd);
      if (!Number.isFinite(usd) || usd <= 0 || usd > CAP_CEILING) {
        throw new HttpError(400, `daily budget must be more than $0 and at most $${CAP_CEILING}`);
      }
      await d.capStore.set(Math.round(usd * 100) / 100);
      return json({ max_daily_usd: await d.capStore.get() });
    }

    // The cap is on list-price equivalent, so it also limits how much of a subscription gets used.
    if ((await d.ledger.listToday()) >= cap) throw new HttpError(429, `daily budget of $${cap} reached`);
    const body = (await req.json()) as Record<string, unknown>;
    const spend = async () => {
      const t = (await d.ledger.days(1))[0];
      return { spend_today: t?.actualUsd ?? 0, list_today: t?.listUsd ?? 0 };
    };

    if (path === "/v1/judge") {
      const requested = Number(body.looks_min);
      const looksMin = Number.isInteger(requested) && requested >= 1 && requested <= 10 ? requested : undefined;
      const result = await d.brain.judge(body.profile as Profile, (body.examples as Example[]) ?? [], body.prefilter !== false, (body.comment_edits as CommentEdit[]) ?? [], looksMin);
      return json({ ...result, ...(await spend()) });
    }
    if (path === "/v1/comment") {
      const result = await d.brain.comment(body.profile as Profile, Number(body.target_index), (body.comment_edits as CommentEdit[]) ?? []);
      return json({ ...result, ...(await spend()) });
    }
    if (path === "/v1/opener") {
      const result = await d.brain.opener(body.profile as Profile, (body.chat as string[]) ?? [], (body.recent_openers as string[]) ?? []);
      return json({ ...result, ...(await spend()) });
    }
    throw new HttpError(404, "not found");
  } catch (e) {
    if (e instanceof HttpError) return json({ error: e.message }, e.status);
    return json({ error: e instanceof Error ? e.message : String(e) }, 502);
  }
}
