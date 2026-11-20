import type { Backend, CallRequest, CallResult, ContentBlock } from "../brain";

/** claude-opus-5-5 → anthropic/claude-opus-5.5. Names that already have a provider pass through. */
export const openRouterModel = (model: string) =>
  model.includes("/") ? model : `anthropic/${model.replace(/-(\d+)-(\d+)$/, "-$1.$2")}`;

const toPart = (b: ContentBlock) =>
  b.type === "text"
    ? { type: "text", text: b.text }
    : { type: "image_url", image_url: { url: `data:${b.source.media_type};base64,${b.source.data}` } };

/** Pay-per-token through OpenRouter, using its OpenAI-style chat API. Costs are priced from prices.json. */
export class OpenRouterBackend implements Backend {
  readonly name = "openrouter" as const;

  constructor(private apiKey: string, private effort: "low" | "medium" | "high", private timeoutMs = 180_000) {}

  async call<T>(req: CallRequest): Promise<CallResult<T>> {
    const started = Date.now();
    const system = req.system.map((text, i) =>
      i === req.system.length - 1 ? { type: "text", text, cache_control: { type: "ephemeral" } } : { type: "text", text },
    );
    const response = await fetch("https://openrouter.ai/api/v1/chat/completions", {
      method: "POST",
      headers: { authorization: `Bearer ${this.apiKey}`, "content-type": "application/json" },
      signal: AbortSignal.timeout(this.timeoutMs),
      body: JSON.stringify({
        model: openRouterModel(req.model),
        max_tokens: req.maxTokens,
        messages: [
          { role: "system", content: system },
          { role: "user", content: req.content.map(toPart) },
        ],
        reasoning: { effort: this.effort },
        response_format: { type: "json_schema", json_schema: { name: "result", strict: true, schema: req.schema } },
        provider: { require_parameters: true },
      }),
    });
    if (!response.ok) throw new Error(`openrouter ${response.status}: ${(await response.text()).slice(0, 300)}`);
    const body = (await response.json()) as Record<string, any>;
    if (body.error) throw new Error(`openrouter: ${String(body.error.message ?? body.error).slice(0, 300)}`);

    const u = body.usage ?? {};
    const cacheRead = u.prompt_tokens_details?.cached_tokens ?? 0;
    const cacheWrite = u.prompt_tokens_details?.cache_write_tokens ?? 0;
    const usage = { input: Math.max((u.prompt_tokens ?? 0) - cacheRead - cacheWrite, 0), output: u.completion_tokens ?? 0, cacheRead, cacheWrite };
    // Keep our own model name so the call is priced from prices.json.
    const base = { model: req.model, usage, durationMs: Date.now() - started };
    const choice = body.choices?.[0];
    if (choice?.message?.refusal || choice?.native_finish_reason === "refusal") return { ...base, data: null, refused: true };
    try {
      return { ...base, data: JSON.parse(choice?.message?.content ?? "") as T, refused: false };
    } catch {
      return { ...base, data: null, refused: false };
    }
  }
}
