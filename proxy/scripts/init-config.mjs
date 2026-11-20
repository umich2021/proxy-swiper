// Copies config.example/* into config/ for any file that isn't there yet, so a fresh clone runs.
// config/ holds your own preferences, facts and voice samples and is never committed.
import { copyFileSync, existsSync, mkdirSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const from = join(root, "config.example");
const to = join(root, "config");
mkdirSync(to, { recursive: true });
const made = [];
for (const f of readdirSync(from)) {
  if (existsSync(join(to, f))) continue;
  copyFileSync(join(from, f), join(to, f));
  made.push(f);
}
if (made.length) console.log(`Made config/${made.join(", config/")} from the templates. Edit them before you use the app.`);
