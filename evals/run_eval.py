#!/usr/bin/env python3
"""Replay your labeled profiles through the proxy and measure how often the AI agrees with you.

The oldest N labels become the few-shot examples (held fixed so the prompt caches); the rest are
the test set. Run this before and after changing preferences, prompts, model or effort.

Usage:
  python3 run_eval.py autodate_YYYYMMDD_HHMMSS.zip --proxy https://autodate-proxy.<you>.workers.dev --token <DEVICE_TOKEN>
  options: --examples 16  --limit 100  --no-prefilter

Costs real money (about $0.03 per profile on Opus). --limit caps it.
"""
import argparse
import base64
import json
import re
import sys
import urllib.error
import urllib.request
import zipfile


def judge(proxy: str, token: str, body: dict) -> dict:
    req = urllib.request.Request(
        proxy.rstrip("/") + "/v1/judge",
        data=json.dumps(body).encode(),
        headers={"authorization": f"Bearer {token}", "content-type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        return {"error": f"HTTP {e.code}: {e.read().decode()[:200]}"}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("export")
    ap.add_argument("--proxy", required=True)
    ap.add_argument("--token", required=True)
    ap.add_argument("--examples", type=int, default=16)
    ap.add_argument("--limit", type=int, default=100)
    ap.add_argument("--no-prefilter", action="store_true")
    args = ap.parse_args()

    z = zipfile.ZipFile(args.export)
    db = json.loads(z.read("db.json"))
    profiles = {p["key"]: p for p in db["profiles"]}
    images: dict[str, list[tuple[int, str]]] = {}
    for name in z.namelist():
        m = re.search(r"profiles/([0-9a-f]+)/(\d+)\.jpg$", name)
        if m:
            images.setdefault(m[1], []).append((int(m[2]), base64.b64encode(z.read(name)).decode()))

    # Latest Hinge action per profile. Offline reviews supply the genuine decision; strategic likes
    # are actions, not preference labels, so they stay out of training and evaluation.
    latest: dict[str, dict] = {}
    for lab in sorted(db["labels"], key=lambda r: r["id"]):
        latest[lab["profile_key"]] = lab
    reviews = {r["profile_key"]: r for r in db.get("profile_reviews", [])}
    labels = []
    strategic = 0
    for original in latest.values():
        lab = dict(original)
        review = reviews.get(lab["profile_key"])
        if review:
            if review.get("strategy_like"):
                strategic += 1
                continue
            if review.get("genuine_verdict") in ("like", "skip"):
                lab["verdict"] = review["genuine_verdict"]
            notes = [lab["note"]] if lab.get("note") else []
            physical = review.get("physical")
            if physical is not None:
                notes.append("your physical attraction: unclear" if physical == 0 else f"your physical attraction: {physical}/10")
            if review.get("personality"):
                notes.append(f"your personality fit: {review['personality']}")
            if review.get("note"):
                notes.append(f"your type notes: {review['note']}")
            if notes:
                lab["note"] = "\n".join(notes)
        labels.append(lab)
    if strategic:
        print(f"Excluded {strategic} strategic likes from training and evaluation")
    if len(labels) <= args.examples:
        sys.exit(f"Only {len(labels)} labeled profiles; need more than --examples={args.examples}.")

    train, test = labels[: args.examples], labels[args.examples :][: args.limit]
    examples = [{"text": l["text"], "verdict": l["verdict"], **({"note": l["note"]} if l.get("note") else {})} for l in train]
    print(f"{len(train)} examples, testing {len(test)} profiles\n")

    tp = fp = fn = tn = errors = 0
    cost = 0.0
    for lab in test:
        p = profiles.get(lab["profile_key"], {})
        body = {
            "profile": {
                "text": lab["text"],
                "images": [b for _, b in sorted(images.get(lab["profile_key"], []))],
                "likeables": json.loads(p.get("likeables") or "[]"),
            },
            "examples": examples,
            "prefilter": not args.no_prefilter,
        }
        r = judge(args.proxy, args.token, body)
        if "error" in r:
            errors += 1
            print(f"  ! {lab['profile_key']}: {r['error']}")
            if r["error"].startswith("HTTP 429"):
                break
            continue
        cost += r.get("cost_usd", 0)
        ai, you = r["decision"] == "like", lab["verdict"] == "like"
        tp += ai and you
        fp += ai and not you
        fn += you and not ai
        tn += not ai and not you
        mark = "✓" if ai == you else "✗"
        print(f"  {mark} you={lab['verdict']:4} ai={r['decision']:4} {r['score']:3}  {(r.get('reasons') or [''])[0][:70]}")

    n = tp + fp + fn + tn
    if n == 0:
        sys.exit("No successful judgements.")
    precision = tp / (tp + fp) if tp + fp else float("nan")
    recall = tp / (tp + fn) if tp + fn else float("nan")
    print(f"\nAgreement:       {(tp + tn) / n:.0%}  ({tp + tn}/{n})")
    print(f"Like precision:  {precision:.0%}  (when the AI likes, you would too; target >= 85%)")
    print(f"Like recall:     {recall:.0%}  (of the ones you liked, how many the AI caught)")
    print(f"Errors: {errors}   Cost: ${cost:.2f}")


if __name__ == "__main__":
    main()
