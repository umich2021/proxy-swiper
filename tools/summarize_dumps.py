#!/usr/bin/env python3
"""Summarize an autoDate Inspector export so we can answer the Phase 0 questions:

  - Can we read profile text from the node tree?
  - Which nodes are the like / skip / comment / send buttons (id, description, custom action)?
  - Does Hinge block screenshots anywhere?

Usage:  python3 summarize_dumps.py autodate_YYYYMMDD_HHMMSS.zip   (or an extracted folder)
"""
import json
import re
import sys
import zipfile
from collections import Counter
from pathlib import Path


def load(src: Path) -> dict[str, bytes]:
    if src.is_dir():
        return {str(p.relative_to(src)): p.read_bytes() for p in src.rglob("*") if p.is_file()}
    with zipfile.ZipFile(src) as z:
        return {n: z.read(n) for n in z.namelist() if not n.endswith("/")}


def walk(node: dict):
    yield node
    for child in node.get("children", []):
        yield from walk(child)


def label(n: dict) -> str:
    parts = [n.get("id"), n.get("desc") and f'desc="{n["desc"]}"', n.get("text") and f'text="{n["text"][:40]}"']
    if n.get("actions"):
        parts.append(f"actions={n['actions']}")
    return " ".join(p for p in parts if p) or f"<{n.get('cls')} no id/text>"


def main() -> None:
    files = load(Path(sys.argv[1]))
    dumps = sorted(k for k in files if re.search(r"(^|/)\d{4}_[a-z_]+\.json$", k))
    shots = {k.removesuffix(".jpg") for k in files if k.endswith(".jpg")}

    print(f"{len(dumps)} screen dumps, {len(shots)} screenshots\n")

    screens = Counter()
    for k in dumps:
        doc = json.loads(files[k])
        screens[doc.get("screen")] += 1
        nodes = list(walk(doc["tree"]))
        clickable = [n for n in nodes if "clickable" in n.get("flags", [])]
        unlabeled = [n for n in clickable if not (n.get("id") or n.get("desc") or n.get("text") or n.get("actions"))]
        has_shot = "yes" if k.rsplit("_", 1)[0] in shots else "NO"
        print(f"--- {k}  screen={doc.get('screen')}  nodes={len(nodes)}  clickable={len(clickable)} "
              f"(unlabeled {len(unlabeled)})  screenshot={has_shot}")
        print("    text:", " / ".join(doc["texts"][:12])[:300])

    print("\nScreens seen:")
    for name, count in screens.most_common():
        print(f"  {count:4d}  {name}")

    clicks = [k for k in files if k.endswith("clicks.jsonl")]
    taps = Counter()
    for k in clicks:
        for line in files[k].decode().splitlines():
            c = json.loads(line)
            node = c.get("node") or {"desc": c.get("desc"), "text": c.get("text"), "cls": c.get("cls")}
            matched = c.get("matched_element") or "unmatched"
            taps[(c.get("screen"), f"{label(node)}  -> {matched}")] += 1
    print("\nButtons you tapped (these are the agent's future targets):")
    for (screen, what), count in taps.most_common(40):
        print(f"  {count:4d}  [{(screen or '?').rsplit('.', 1)[-1]}]  {what}")

    notes = [k for k in files if k.endswith("notes.txt")]
    problems = [line for k in notes for line in files[k].decode().splitlines()]
    if problems:
        print("\nScreenshot notes:")
        for line in Counter(p.split(" ", 1)[1] for p in problems).most_common():
            print(f"  {line[1]:4d}  {line[0]}")


if __name__ == "__main__":
    main()
