# Preferences & Opener Spec

How you tell the agent what you want, how it scores profiles, and how it writes in your voice.

## 1. Three layers of preference

| Layer | Where it lives | Enforced by |
|---|---|---|
| **Hard filters** (age, distance, height, religion, kids, etc.) | Hinge's own filter settings (plus dealbreakers with Hinge+) | Hinge. Those profiles never reach the agent, so the cheapest filter is free |
| **Explicit rules** (must-haves, never-list, plusses) | `proxy/config/preferences.json` (edit, then `npx wrangler deploy`) | Judge prompt + code checks on `dealbreakers_hit` |
| **Learned taste** | Labels from your manual swipes in shadow mode and your approvals and overrides | Few-shot examples in the prompt, refreshed weekly |

Learned taste ends up doing most of the work. Written rules capture maybe 60% of what you want, and the labels fill in the rest.

## 2. Preferences file (template)

```json
{
  "about_me_summary": "28, software engineer in Brooklyn. Into climbing, cooking, and live music. Looking for something long-term.",
  "looking_for": {
    "relationship_intent": ["long-term", "long-term-open-to-short"],
    "must_have": ["at least one prompt answered with real effort (not one-word)"],
    "strong_plus": [
      "active / outdoorsy (climbing, hiking, running)",
      "cooks or loves food",
      "dry or playful humor in prompts",
      "creative work or hobbies"
    ],
    "mild_plus": ["has a dog", "travels"],
    "neutral": ["astrology"],
    "mild_minus": ["only group photos where it's unclear who they are"],
    "never": [
      "looking for something casual only",
      "smokes cigarettes",
      "not single / looking for a third"
    ]
  },
  "photo_guidance": "Prefer clear solo photos and at least one candid/activity photo. Attractiveness is the looks score, not this.",
  "looks": { "min": 6 },
  "thresholds": { "like": 65, "ask_me_below": 55 }
}
```

### Looks
`looks.min` is a hard floor on a 1–10 conventional face score. 5 is an average profile, 6 mildly above average, 7 clearly attractive, 8 about the top 7%. Code skips anyone under the floor when a face is visible. A hidden face does not trip it.

This is not Hinge's ranker. Hinge predicts who would mutually like whom (a learned embedding, then Gale–Shapley). We don't have that graph, so this is the consensus factor from face research: averageness, symmetry, femininity, skin clarity. Your type is the residual. The judge writes a short `type_read` on each label; later examples use those pairs to learn which faces you like among people who clear the bar. The number itself does not move to match your type.

## 3. Scoring rubric (given to the judge)

| Component | Weight | Signal |
|---|---|---|
| Intent & dealbreakers | gate | Any `never` hit or intent mismatch → skip |
| Shared interests / lifestyle | 35 | Overlap with `strong_plus` / `mild_plus` |
| Profile effort & personality | 25 | Thoughtful prompts, humor, specificity |
| Looks | gate | Below `looks.min` with a visible face → skip |
| Photo signals | 25 | Story and effort per `photo_guidance`, plus how far above the looks floor |
| Conversation potential | 15 | Is there a clear hook for a good opener? |

Target: **≥ 85% precision on likes** against your labels. When the agent likes someone, you'd have liked them too. Recall matters less, because a missed match costs less than a bad like.

## 4. Learning loop

1. **Collect profiles:** Shadow mode saves each profile you swipe on, and Approve mode saves profiles shown on its card. Screenshots are kept for 30 days.
2. **Review physical type offline:** the app's **Review** tab shows the saved profiles without Hinge or API calls. Rate only your private physical attraction (1–10 or unclear) and optionally add type notes. Your original Hinge swipe is not the physical label, so a strategic like cannot contaminate this rating.
3. **Approve / auto modes:** every approve, edit, override, and skip you make on the overlay card becomes a label for the overall decision. This stays separate from the physical review.
4. **Weekly:** pick 10–20 few-shot examples covering likes and skips, with priority on recent **disagreements**. Notes carry the AI's consensus looks read and your private physical score separately.
5. Re-run eval on your laptop (TECH.md §12). If agreement drops, roll back the example set.
6. **Monthly:** review disagreements and turn recurring patterns into explicit rules.

## 5. Opener / comment rules

### Inputs to the writer
- The chosen `like_target` (the specific photo or prompt)
- `proxy/config/facts.json`: true facts about you that may be referenced (job, hobbies, neighborhood, pets). **The writer may not invent anything about you.**
- `proxy/config/voice_samples.md`: 10–20 real messages you've sent, so it learns your tone, capitalization, and punctuation

### Style rules (defaults; edit to taste)
- Reference something **specific** from their profile. Generic openers aren't allowed.
- One easy, fun question or playful observation that invites a reply.
- 1–2 sentences, ≤ 200 characters.
- Match your voice samples. If you text in lowercase, it does too.
- No pickup lines, negging, comments on bodies, innuendo, or mention of AI.
- At most one emoji. Don't open with their name.

### Examples of target quality

| Their prompt | Bad | Good |
|---|---|---|
| "My simple pleasures: farmers market on Saturday mornings" | "Hey beautiful 😍" | "ok which stall is the non-negotiable first stop" |
| Photo: bouldering | "You look great in this pic" | "what grade are you projecting right now? I've been stuck on the same V4 for a month" *(only if facts.json says you climb)* |
| "I'll fall for you if… you can make me laugh at a funeral" | "I'm hilarious, try me" | "high bar. is a well-timed pun allowed or is that cheating" |

### Post-match openers
Same rules. If they sent a comment when they liked you, reply to **their** comment. That's the strongest opener there is.

## 6. Handoff
After the opener, the agent never touches that thread again. It marks the match `handed_off` and sends you a notification.
