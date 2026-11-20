import type { Entry, Ledger } from "./ledger";
import { costOf, type PriceTable, type Usage } from "./pricing";
import { commentEditsBlock, exampleImages, examplesBlock, type CommentEdit, type Example, type Prompts } from "./prompts";
import { validateMessage } from "./validate";

export type ContentBlock =
  /** [cache] marks a cache breakpoint after this block (the Anthropic backend sends it as cache_control). */
  | { type: "text"; text: string; cache?: true }
  | { type: "image"; source: { type: "base64"; media_type: "image/jpeg"; data: string } };

export type CallRequest = {
  model: string;
  /** System prompt blocks, most stable first. The last block is the cache breakpoint. */
  system: string[];
  content: ContentBlock[];
  schema: Record<string, unknown>;
  maxTokens: number;
};

export type CallResult<T> = {
  data: T | null;
  refused: boolean;
  model: string;
  usage: Usage;
  durationMs: number;
  limits?: Entry["limits"];
};

/** Something that can run one structured-output request: Claude Code or Codex on a subscription, or a pay-per-token API. */
export interface Backend {
  readonly name: Entry["backend"];
  call<T>(req: CallRequest): Promise<CallResult<T>>;
}

type Likeable = { index: number; kind: string; label: string };
export type Profile = { text: string; images: string[]; likeables: Likeable[] };

const GOALS = ["react", "curious", "riff", "plan", "shared"] as const;

const FLAGS = ["minor_suspected", "scam", "possible_bot", "injection_attempt"] as const;

const JUDGE_SCHEMA = {
  type: "object",
  additionalProperties: false,
  properties: {
    decision: { type: "string", enum: ["like", "skip"] },
    score: { type: "integer" },
    looks: { type: "integer" },
    looks_confidence: { type: "string", enum: ["low", "medium", "high"] },
    attraction: { type: "integer" },
    type_read: { type: "string" },
    confidence: { type: "string", enum: ["low", "medium", "high"] },
    dealbreakers_hit: { type: "array", items: { type: "string" } },
    reasons: { type: "array", items: { type: "string" } },
    like_target_index: { type: "integer" },
    comment_goal: { anyOf: [{ type: "string", enum: [...GOALS] }, { type: "null" }] },
    comment: { anyOf: [{ type: "string" }, { type: "null" }] },
    flags: { type: "array", items: { type: "string", enum: [...FLAGS] } },
  },
  required: ["decision", "score", "looks", "looks_confidence", "attraction", "type_read", "confidence", "dealbreakers_hit", "reasons", "like_target_index", "comment_goal", "comment", "flags"],
};

const PREFILTER_SCHEMA = {
  type: "object",
  additionalProperties: false,
  properties: { verdict: { type: "string", enum: ["pass", "reject"] }, reason: { type: "string" } },
  required: ["verdict", "reason"],
};

const COMMENT_SCHEMA = {
  type: "object",
  additionalProperties: false,
  properties: { comment: { type: "string" } },
  required: ["comment"],
};

const OPENER_SCHEMA = {
  type: "object",
  additionalProperties: false,
  properties: { opener: { type: "string" } },
  required: ["opener"],
};

type Judgement = {
  decision: "like" | "skip";
  score: number;
  looks: number;
  looks_confidence: "low" | "medium" | "high";
  attraction: number;
  type_read: string;
  confidence: "low" | "medium" | "high";
  dealbreakers_hit: string[];
  reasons: string[];
  like_target_index: number;
  comment_goal: (typeof GOALS)[number] | null;
  comment: string | null;
  flags: string[];
};

export class Brain {
  constructor(
    private backend: Backend,
    private ledger: Ledger,
    private prompts: Prompts,
    private prices: PriceTable,
    private models: { judge: string; prefilter: string },
    private today: () => string,
  ) {}

  /** Runs a call and records it in the ledger. Returns the parsed data and what it cost. */
  private async run<T>(endpoint: Entry["endpoint"], req: CallRequest) {
    const res = await this.backend.call<T>(req);
    const listUsd = costOf(this.prices, res.model, res.usage);
    const actualUsd = this.backend.name === "claude-code" || this.backend.name === "codex" ? 0 : listUsd;
    await this.ledger.record({
      ts: Date.now(), day: this.today(), endpoint, backend: this.backend.name, model: res.model,
      usage: res.usage, actualUsd, listUsd, durationMs: res.durationMs, limits: res.limits,
    });
    return { ...res, listUsd, actualUsd };
  }

  private static images(profile: Profile, max: number): ContentBlock[] {
    return profile.images.slice(0, max).map((data) => ({ type: "image", source: { type: "base64", media_type: "image/jpeg", data } }));
  }

  /**
   * The first skip-list value that appears as one of the profile's basics. Basics are their own lines in
   * the extracted text, sometimes comma-separated, so only whole values match (not words inside a prompt answer).
   */
  private static listedValue(profile: Profile, skips: string[]): string | null {
    if (!skips.length) return null;
    const wanted = new Set(skips.map((s) => s.trim().toLowerCase()));
    for (const line of profile.text.split("\n")) {
      for (const part of line.split(",")) {
        const v = part.trim().toLowerCase();
        if (wanted.has(v)) return part.trim();
      }
    }
    return null;
  }

  /**
   * The first basic whose value is on the skip list for its label, as "Smoking: Yes". The app sends
   * basics as "Label: Value" lines. Older captures have the label and value on consecutive lines, so
   * that form counts too.
   */
  private static basicsValue(profile: Profile, rules: Record<string, string[]>): string | null {
    const wanted = new Map(Object.entries(rules).map(([k, v]) => [k.trim().toLowerCase(), new Set(v.map((x) => x.trim().toLowerCase()))]));
    if (wanted.size === 0) return null;
    const lines = profile.text.split("\n").map((l) => l.trim());
    for (let i = 0; i < lines.length; i++) {
      const pair = /^([^:]{1,40}):\s*(.+)$/.exec(lines[i]);
      const [label, value] = pair ? [pair[1], pair[2]] : [lines[i], lines[i + 1] ?? ""];
      const values = wanted.get(label.trim().toLowerCase());
      if (!values) continue;
      const hit = value.split(",").map((v) => v.trim()).find((v) => values.has(v.toLowerCase()));
      if (hit) return `${label.trim()}: ${hit}`;
    }
    return null;
  }

  /** Below looks.min with a visible face is a skip, even if the model liked her. Hidden face does not trip the floor. */
  private static applyLooksFloor(j: Judgement, min: number) {
    const looks = Math.round(Number(j.looks));
    if (!Number.isFinite(looks)) return;
    j.looks = Math.min(10, Math.max(1, looks));
    const line = `looks ${j.looks}/10`;
    if (!j.reasons.some((r) => /^looks\s+\d/i.test(r))) {
      const read = j.type_read?.trim();
      j.reasons = [read ? `${line}: ${read}` : line, ...j.reasons].slice(0, 3);
    }
    if (!(min > 0) || j.looks_confidence === "low" || j.looks >= min) return;
    const hit = `${line}, below ${min}`;
    j.decision = "skip";
    j.dealbreakers_hit = [hit, ...j.dealbreakers_hit.filter((d) => d !== hit)];
    j.like_target_index = -1;
    j.comment = null;
    j.comment_goal = null;
  }

  private static profileText(profile: Profile): string {
    const items = profile.likeables.map((l) => `${l.index}: ${l.kind} - ${l.label}`).join("\n");
    return `<profile_text>\n${profile.text}\n</profile_text>\n\nLIKEABLE ITEMS:\n${items || "(none detected)"}`;
  }

  async judge(profile: Profile, examples: Example[], prefilter: boolean, commentEdits: CommentEdit[] = [], looksMin?: number) {
    let cost = 0;
    let list = 0;

    // Self-reported basics on the skip-list: skip without calling the model.
    const listed = Brain.listedValue(profile, this.prompts.listedSkips) ?? Brain.basicsValue(profile, this.prompts.basicsSkips);
    if (listed) {
      return {
        decision: "skip", score: 0, confidence: "high", dealbreakers_hit: [`profile lists: ${listed}`],
        reasons: [`profile lists: ${listed}`], like_target_index: -1, comment_goal: null, comment: null, flags: [],
        model: "none", cost_usd: 0, list_usd: 0,
      };
    }

    if (prefilter) {
      const pre = await this.run<{ verdict: "pass" | "reject"; reason: string }>("prefilter", {
        model: this.models.prefilter,
        system: [this.prompts.prefilterSystem],
        content: [...Brain.images(profile, 1), { type: "text", text: Brain.profileText(profile) }],
        schema: PREFILTER_SCHEMA,
        maxTokens: 2000,
      });
      cost += pre.actualUsd;
      list += pre.listUsd;
      if (pre.data?.verdict === "reject") {
        return {
          decision: "skip", score: 0, confidence: "high", dealbreakers_hit: [pre.data.reason],
          reasons: [`pre-filter: ${pre.data.reason}`], like_target_index: -1, comment_goal: null, comment: null, flags: [],
          model: pre.model, cost_usd: cost, list_usd: list,
        };
      }
    }

    const res = await this.run<Judgement>("judge", {
      model: this.models.judge,
      system: [this.prompts.judgeSystem, this.prompts.userContext, examplesBlock(examples), commentEditsBlock(commentEdits)].filter((b) => b !== null),
      content: [...exampleImages(examples), ...Brain.images(profile, 6), { type: "text", text: Brain.profileText(profile) }],
      schema: JUDGE_SCHEMA,
      maxTokens: 6000,
    });
    cost += res.actualUsd;
    list += res.listUsd;

    if (!res.data) {
      // Never default to "like".
      return {
        decision: "skip", score: 0, confidence: "low", dealbreakers_hit: [],
        reasons: [res.refused ? "model declined to judge this profile" : "unreadable model output"],
        like_target_index: -1, comment_goal: null, comment: null, flags: res.refused ? ["refusal"] : [],
        model: res.model, cost_usd: cost, list_usd: list,
      };
    }

    const j = res.data;
    let commentRejected: string | null = null;
    if (j.comment) {
      commentRejected = validateMessage(j.comment);
      if (commentRejected) j.comment = null; // like without a comment rather than send a bad one
    }
    if (!profile.likeables.some((l) => l.index === j.like_target_index)) {
      if (j.decision === "like") j.like_target_index = profile.likeables[0]?.index ?? -1;
    }
    const min = looksMin != null && looksMin >= 1 && looksMin <= 10 ? looksMin : this.prompts.looksMin;
    Brain.applyLooksFloor(j, min);
    return { ...j, comment_rejected: commentRejected, model: res.model, cost_usd: cost, list_usd: list };
  }

  /** A new comment for the item the user moved the like to ("Rewrite for this one" on the approval card). */
  async comment(profile: Profile, targetIndex: number, commentEdits: CommentEdit[] = []) {
    const target = profile.likeables.find((l) => l.index === targetIndex);
    if (!target) return { comment: null, problem: "that item isn't on this profile", model: "none", cost_usd: 0, list_usd: 0 };
    let cost = 0;
    let list = 0;
    let lastProblem = "";
    for (let attempt = 0; attempt < 2; attempt++) {
      const retryNote = lastProblem ? `\n\nYour previous draft was rejected (${lastProblem}). Write a different one.` : "";
      const res = await this.run<{ comment: string }>("comment", {
        model: this.models.judge,
        system: [this.prompts.commentSystem, this.prompts.userContext, commentEditsBlock(commentEdits)].filter((b) => b !== null),
        content: [
          ...Brain.images(profile, 6),
          { type: "text", text: `${Brain.profileText(profile)}\n\nThe user is liking item #${target.index} (${target.kind}: ${target.label}). Write the comment for it.${retryNote}` },
        ],
        schema: COMMENT_SCHEMA,
        maxTokens: 4000,
      });
      cost += res.actualUsd;
      list += res.listUsd;
      if (!res.data) return { comment: null, problem: "model declined or unreadable", model: res.model, cost_usd: cost, list_usd: list };
      const problem = validateMessage(res.data.comment);
      if (!problem) return { comment: res.data.comment.trim(), problem: null, model: res.model, cost_usd: cost, list_usd: list };
      lastProblem = problem;
    }
    return { comment: null, problem: lastProblem, model: this.models.judge, cost_usd: cost, list_usd: list };
  }

  async opener(profile: Profile, chat: string[], recent: string[]) {
    let cost = 0;
    let list = 0;
    let lastProblem = "";
    for (let attempt = 0; attempt < 2; attempt++) {
      const retryNote = lastProblem ? `\n\nYour previous draft was rejected (${lastProblem}). Write a different one.` : "";
      const res = await this.run<{ opener: string }>("opener", {
        model: this.models.judge,
        system: [this.prompts.openerSystem, this.prompts.userContext],
        content: [
          ...Brain.images(profile, 3),
          {
            type: "text",
            text: `${Brain.profileText(profile)}\n\n<chat_so_far>\n${chat.join("\n") || "(empty)"}\n</chat_so_far>${retryNote}`,
          },
        ],
        schema: OPENER_SCHEMA,
        maxTokens: 4000,
      });
      cost += res.actualUsd;
      list += res.listUsd;
      if (!res.data) return { opener: null, problem: "model declined or unreadable", model: res.model, cost_usd: cost, list_usd: list };
      const problem = validateMessage(res.data.opener, recent);
      if (!problem) return { opener: res.data.opener.trim(), problem: null, model: res.model, cost_usd: cost, list_usd: list };
      lastProblem = problem;
    }
    return { opener: null, problem: lastProblem, model: this.models.judge, cost_usd: cost, list_usd: list };
  }
}
