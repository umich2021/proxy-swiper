// Checks every message before it can reach a real person. Returns a reason string when it fails.

const BLOCKLIST = [
  /\b(sexy|hot body|dtf|netflix and chill|daddy|kinky)\b/i,
  /\b(are you a (parking ticket|magician)|did it hurt when)\b/i,
  /\b(as an ai|language model|chatgpt|claude)\b/i,
  /\b(instagram|snap(chat)?|telegram|whatsapp|venmo|cashapp|crypto)\b/i,
];

const URL = /(https?:\/\/|www\.|\.\w{2,4}\/)/i;
const PHONE = /(\+?\d[\d\s().-]{7,}\d)/;
const EMAIL = /\S+@\S+\.\S+/;
const EMOJI = /\p{Extended_Pictographic}/gu;

function trigrams(s: string): Set<string> {
  const t = s.toLowerCase().replace(/[^a-z0-9 ]/g, "");
  const out = new Set<string>();
  for (let i = 0; i < t.length - 2; i++) out.add(t.slice(i, i + 3));
  return out;
}

function similarity(a: string, b: string): number {
  const x = trigrams(a);
  const y = trigrams(b);
  if (x.size === 0 || y.size === 0) return 0;
  let both = 0;
  for (const g of x) if (y.has(g)) both++;
  return both / (x.size + y.size - both);
}

export function validateMessage(text: string, recent: string[] = []): string | null {
  const t = text.trim();
  if (t.length === 0) return "empty";
  if (t.length > 200) return "too long";
  if ((t.match(EMOJI) ?? []).length > 1) return "too many emoji";
  if (URL.test(t)) return "contains a link";
  if (PHONE.test(t)) return "contains a phone number";
  if (EMAIL.test(t)) return "contains an email";
  for (const re of BLOCKLIST) if (re.test(t)) return `blocked phrase (${re.source.slice(0, 30)})`;
  for (const r of recent) if (similarity(t, r) > 0.6) return "too similar to a recent opener";
  return null;
}
