import type { Backend, CallRequest, CallResult, ContentBlock } from "../brain";

/**
 * Pay-per-token providers that speak OpenAI's chat completions API: OpenAI (GPT), xAI (Grok) and
 * Moonshot (Kimi). The model must read images, since the judge sends profile photos.
 */
export const PROVIDERS = {
  openai: { url: "https://api.openai.com/v1/chat/completions", keyVar: "OPENAI_API_KEY", schema: "json_schema", effort: true },
  xai: { url: "https://api.x.ai/v1/chat/completions", keyVar: "XAI_API_KEY", schema: "json_schema", effort: false },
  kimi: { url: "https://api.moonshot.ai/v1/chat/completions", keyVar: "MOONSHOT_API_KEY", schema: "json_object", effort: false },
} as const;

export type ProviderName = keyof typeof PROVIDERS;

const toPart = (b: ContentBlock) =>
  b.type === "text"
    ? { type: "text", text: b.text }
    : { type: "image_url", image_url: { url: `data:${b.source.media_type};base64,${b.source.data}` } };

export class OpenAICompatibleBackend implements Backend {
  readonly name: ProviderName;

  constructor(provider: ProviderName, private apiKey: string, private effort: string, private timeoutMs = 180_000) {
    this.name = provider;
  }

  async call<T>(req: CallRequest): Promise<CallResult<T>> {
    const started = Date.now();
    const p = PROVIDERS[this.name];
    let system = req.system.join("\n\n");
    // Providers without strict JSON schemas get JSON mode plus the schema written into the prompt.
    if (p.schema === "json_object") {
      system += `\n\nReply with one JSON object only, matching this JSON Schema:\n${JSON.stringify(req.schema)}`;
    }
    const response = await fetch(p.url, {
      method: "POST",
      headers: { authorization: `Bearer ${this.apiKey}`, "content-type": "application/json" },
      signal: AbortSignal.timeout(this.timeoutMs),
      body: JSON.stringify({
        model: req.model,
        max_completion_tokens: req.maxTokens,
        messages: [
          { role: "system", content: system },
          { role: "user", content: req.content.map(toPart) },
        ],
        ...(p.effort && this.effort ? { reasoning_effort: this.effort } : {}),
        response_format: p.schema === "json_schema"
          ? { type: "json_schema", json_schema: { name: "result", strict: true, schema: req.schema } }
          : { type: "json_object" },
      }),
    });
    if (!response.ok) throw new Error(`${this.name} ${response.status}: ${(await response.text()).slice(0, 300)}`);
    const body = (await response.json()) as Record<string, any>;
    if (body.error) throw new Error(`${this.name}: ${String(body.error.message ?? body.error).slice(0, 300)}`);

    const u = body.usage ?? {};
    const cacheRead = u.prompt_tokens_details?.cached_tokens ?? 0;
    const usage = { input: Math.max((u.prompt_tokens ?? 0) - cacheRead, 0), output: u.completion_tokens ?? 0, cacheRead, cacheWrite: 0 };
    const base = { model: req.model, usage, durationMs: Date.now() - started };
    const choice = body.choices?.[0];
    if (choice?.message?.refusal) return { ...base, data: null, refused: true };
    return { ...base, data: parseJson<T>(choice?.message?.content), refused: false };
  }
}

/** The JSON object in a reply, tolerating a ```json fence or text around it. */
export function parseJson<T>(text: unknown): T | null {
  if (typeof text !== "string") return null;
  try {
    return JSON.parse(text) as T;
  } catch {
    const start = text.indexOf("{");
    const end = text.lastIndexOf("}");
    if (start < 0 || end <= start) return null;
    try {
      return JSON.parse(text.slice(start, end + 1)) as T;
    } catch {
      return null;
    }
  }
}
