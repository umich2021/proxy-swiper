import { spawn } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { Backend, CallRequest, CallResult } from "../brain";

/**
 * Runs each request through Claude Code on this computer (`claude -p`), logged in with your
 * Claude subscription. No API key. Tools, MCP servers, settings and CLAUDE.md are all off, and the
 * default system prompt is replaced, so the call is just the model plus our prompt.
 */
export class ClaudeCodeBackend implements Backend {
  readonly name = "claude-code" as const;
  private cwd = mkdtempSync(join(tmpdir(), "autodate-cc-")); // empty dir: no CLAUDE.md to pick up

  constructor(private effort: string, private timeoutMs = 180_000) {}

  call<T>(req: CallRequest): Promise<CallResult<T>> {
    const started = Date.now();
    const args = [
      "-p",
      "--input-format", "stream-json",
      "--output-format", "stream-json",
      "--verbose",
      "--tools", "",
      "--strict-mcp-config",
      "--setting-sources", "",
      "--no-session-persistence",
      "--model", req.model,
      "--effort", this.effort,
      "--system-prompt", req.system.join("\n\n"),
      "--json-schema", JSON.stringify(req.schema),
    ];
    // An API key in the environment would make Claude Code bill the API instead of your subscription.
    const env = { ...process.env };
    delete env.ANTHROPIC_API_KEY;
    delete env.ANTHROPIC_AUTH_TOKEN;

    return new Promise((resolve, reject) => {
      const child = spawn("claude", args, { cwd: this.cwd, env, stdio: ["pipe", "pipe", "pipe"] });
      const timer = setTimeout(() => child.kill("SIGKILL"), this.timeoutMs);
      let buf = "";
      let stderr = "";
      let result: Record<string, any> | null = null;
      let limits: CallResult<T>["limits"];

      child.stdout.on("data", (chunk: Buffer) => {
        buf += chunk.toString();
        let nl: number;
        while ((nl = buf.indexOf("\n")) >= 0) {
          const line = buf.slice(0, nl).trim();
          buf = buf.slice(nl + 1);
          if (!line) continue;
          let ev: Record<string, any>;
          try {
            ev = JSON.parse(line);
          } catch {
            continue;
          }
          if (ev.type === "rate_limit_event") {
            const w = ev.rate_limit_info?.unifiedWindows ?? {};
            limits = { fiveHour: w.five_hour?.utilization, sevenDay: w.seven_day?.utilization };
          }
          if (ev.type === "result") result = ev;
        }
      });
      child.stderr.on("data", (c: Buffer) => (stderr += c.toString()));
      child.on("error", (e) => {
        clearTimeout(timer);
        reject(new Error(`couldn't start claude: ${e.message}`));
      });
      child.on("close", (code) => {
        clearTimeout(timer);
        if (!result) return reject(new Error(`claude exited (${code}) without a result: ${stderr.slice(0, 300)}`));
        if (result.is_error) return reject(new Error(`claude error: ${String(result.result ?? result.subtype).slice(0, 300)}`));
        const u = result.usage ?? {};
        const model = Object.keys(result.modelUsage ?? {})[0] ?? req.model;
        resolve({
          data: (result.structured_output as T) ?? null,
          refused: result.stop_reason === "refusal",
          model,
          usage: {
            input: u.input_tokens ?? 0,
            output: u.output_tokens ?? 0,
            cacheRead: u.cache_read_input_tokens ?? 0,
            cacheWrite: u.cache_creation_input_tokens ?? 0,
          },
          durationMs: Date.now() - started,
          limits,
        });
      });

      child.stdin.end(JSON.stringify({ type: "user", message: { role: "user", content: req.content.map((b) => (b.type === "text" ? { type: "text", text: b.text } : b)) } }) + "\n");
    });
  }
}
