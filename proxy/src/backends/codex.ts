import { spawn } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { Backend, CallRequest, CallResult } from "../brain";
import { parseJson } from "./openaiCompatible";

/**
 * Runs each request through OpenAI's Codex CLI (`codex exec`), logged in with your ChatGPT plan
 * (`codex login`). No API key. Read-only sandbox, a temp folder with no AGENTS.md, nothing saved.
 * Photos go in as image files; the reply is held to the JSON schema with --output-schema.
 */
export class CodexBackend implements Backend {
  readonly name = "codex" as const;

  /** [model] null uses Codex's own default model. */
  constructor(private effort: string, private timeoutMs = 240_000) {}

  call<T>(req: CallRequest): Promise<CallResult<T>> {
    const started = Date.now();
    const dir = mkdtempSync(join(tmpdir(), "autodate-codex-"));
    const schemaFile = join(dir, "schema.json");
    writeFileSync(schemaFile, JSON.stringify(req.schema));
    const images: string[] = [];
    const text: string[] = [];
    for (const b of req.content) {
      if (b.type === "text") text.push(b.text);
      else {
        const f = join(dir, `photo${images.length + 1}.jpg`);
        writeFileSync(f, Buffer.from(b.source.data, "base64"));
        images.push(f);
      }
    }
    const args = [
      "exec",
      "--json",
      "--ephemeral",
      "--skip-git-repo-check",
      "--sandbox", "read-only",
      "--ignore-user-config",
      "--cd", dir,
      "--output-schema", schemaFile,
      // -c values are TOML; a JSON string is a valid TOML string.
      "-c", `developer_instructions=${JSON.stringify(req.system.join("\n\n"))}`,
      "-c", `approval_policy="never"`,
      ...(this.effort ? ["-c", `model_reasoning_effort=${JSON.stringify(this.effort)}`] : []),
      ...(req.model && !req.model.startsWith("claude-") ? ["--model", req.model] : []),
      ...images.flatMap((f) => ["--image", f]),
      text.join("\n\n") || "Go.",
    ];
    // An API key in the environment would bill the API instead of your ChatGPT plan.
    const env = { ...process.env };
    delete env.OPENAI_API_KEY;
    delete env.CODEX_API_KEY;

    return new Promise((resolve, reject) => {
      const done = () => rmSync(dir, { recursive: true, force: true });
      const child = spawn("codex", args, { cwd: dir, env, stdio: ["ignore", "pipe", "pipe"] });
      const timer = setTimeout(() => child.kill("SIGKILL"), this.timeoutMs);
      let buf = "";
      let stderr = "";
      let message: string | null = null;
      let usage = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 };
      let failure: string | null = null;

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
          if (ev.type === "item.completed" && ev.item?.type === "agent_message") message = ev.item.text;
          if (ev.type === "turn.completed" && ev.usage) {
            const u = ev.usage;
            const cached = u.cached_input_tokens ?? 0;
            usage = { input: Math.max((u.input_tokens ?? 0) - cached, 0), output: (u.output_tokens ?? 0) + (u.reasoning_output_tokens ?? 0), cacheRead: cached, cacheWrite: 0 };
          }
          if (ev.type === "turn.failed" || ev.type === "error") failure = String(ev.error?.message ?? ev.message ?? "codex error");
        }
      });
      child.stderr.on("data", (c: Buffer) => (stderr += c.toString()));
      child.on("error", (e) => {
        clearTimeout(timer);
        done();
        reject(new Error(`couldn't start codex (npm install -g @openai/codex, then codex login): ${e.message}`));
      });
      child.on("close", (code) => {
        clearTimeout(timer);
        done();
        if (message == null) return reject(new Error(`codex exited (${code}) without an answer: ${(failure ?? stderr).slice(0, 300)}`));
        resolve({ data: parseJson<T>(message), refused: false, model: req.model, usage, durationMs: Date.now() - started });
      });
    });
  }
}
