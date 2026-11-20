import { buildReport } from "./ledger";
import { FileLedger, ledgerFile, loadConfig } from "./local";

/**
 * `npm run costs [days]`: what autoDate cost on this computer, and what the same usage would cost
 * on every model in config/prices.json. Reads data/ledger.jsonl.
 */
const days = Number(process.argv[2] ?? 30);
const { prices } = loadConfig();
const r = buildReport(await new FileLedger(ledgerFile).days(days), prices);
const $ = (x: number) => (x < 1 ? `$${x.toFixed(3)}` : `$${x.toFixed(2)}`);

if (r.daysWithActivity === 0) {
  console.log(`No calls in the last ${days} days.`);
  process.exit(0);
}

console.log(`Last ${days} days: ${r.daysWithActivity} active days, ${r.total.profiles} profiles judged, ${r.total.openers} openers, ${r.total.calls} model calls\n`);
console.log(`  You actually paid:            ${$(r.total.actualUsd)}   (≈ ${$(r.perMonth.actualUsd)}/month)`);
console.log(`  At API list price it'd cost:  ${$(r.total.listUsd)}   (≈ ${$(r.perMonth.listUsd)}/month)`);
console.log(`  Pace: ≈ ${Math.round(r.perMonth.profiles)} profiles/month\n`);

console.log("What the same usage would cost if everything ran on one model:");
console.log("  model                 total      per month   per profile");
for (const w of [...r.whatIf].sort((a, b) => a.perMonthUsd - b.perMonthUsd)) {
  console.log(`  ${w.model.padEnd(20)}  ${$(w.usd).padStart(8)}   ${$(w.perMonthUsd).padStart(9)}   ${$(w.perProfileUsd).padStart(9)}`);
}
console.log("  (GLM figures use Claude token counts and assume it can read the photos: rough, ±30%.)\n");

if (r.maxWeeklyLimit.length) {
  console.log("Claude subscription weekly limit during autoDate calls (includes anything else you ran at the same time):");
  for (const w of r.maxWeeklyLimit) console.log(`  ${w.day}: ${(w.from * 100).toFixed(0)}% → ${(w.to * 100).toFixed(0)}%`);
}
