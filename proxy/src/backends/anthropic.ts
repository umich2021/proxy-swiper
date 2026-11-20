import Anthropic from "@anthropic-ai/sdk";
import type { Backend, CallRequest, CallResult, ContentBlock } from "../brain";

const toApi = (b: ContentBlock): Anthropic.Beta.BetaContentBlockParam =>
  b.type === "text" && b.cache ? { type: "text", text: b.text, cache_control: { type: "ephemeral" } }
    : b.type === "text" ? { type: "text", text: b.text } : b;

/** Pay-per-token Anthropic API. Works in the Cloudflare Worker and on your PC. */
export class AnthropicBackend implements Backend {
  readonly name = "api" as const;
  private client: Anthropic;

  constructor(apiKey: string, private effort: "low" | "medium" | "high") {
    this.client = new Anthropic({ apiKey });
  }

  async call<T>(req: CallRequest): Promise<CallResult<T>> {
    const started = Date.now();
    // Server-side refusal fallback is supported on the Opus/Sonnet line, not on Haiku.
    const fallback = req.model.includes("haiku")
      ? {}
      : { betas: ["server-side-fallback-2026-07-01"], fallbacks: "default" as const };
    const system: Anthropic.Beta.BetaTextBlockParam[] = req.system.map((text, i) =>
      i === req.system.length - 1 ? { type: "text", text, cache_control: { type: "ephemeral" } } : { type: "text", text },
    );
    const response = await this.client.beta.messages.create({
      model: req.model,
      max_tokens: req.maxTokens,
      system,
      messages: [{ role: "user", content: req.content.map(toApi) }],
      output_config: { effort: this.effort, format: { type: "json_schema", schema: req.schema } },
      ...fallback,
    });
    const u = response.usage;
    const usage = {
      input: u.input_tokens,
      output: u.output_tokens,
      cacheRead: u.cache_read_input_tokens ?? 0,
      cacheWrite: u.cache_creation_input_tokens ?? 0,
    };
    const base = { model: response.model, usage, durationMs: Date.now() - started };
    if (response.stop_reason === "refusal") return { ...base, data: null, refused: true };
    const text = response.content.flatMap((b) => (b.type === "text" ? [b.text] : [])).join("");
    try {
      return { ...base, data: JSON.parse(text) as T, refused: false };
    } catch {
      return { ...base, data: null, refused: false };
    }
  }
}
