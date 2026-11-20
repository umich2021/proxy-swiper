# Roadmap

Each phase has an exit criterion. Don't start the next phase until it's met.

**Status (Oct 19, 2026):** code for Phases 0–5 is written and compiles (`android/`, `proxy/`, `tools/`, `evals/`). Phase 0 has run on a real phone (Galaxy, Android 17). Findings: profile text, prompts, basics, like/skip buttons and tabs are all labeled (`Like photo`, `Like prompt`, `Skip <name>`) and screenshots work everywhere. Hinge does **not** report taps to accessibility services, so shadow mode infers your like/skip from screen changes and the tap-to-calibrate mode was dropped. Still unseen: the like/comment sheet, out-of-likes screen, Matches list and chat. Their selectors are guesses until captured.

## Phase 0 — Accessibility inspector (2–3 days)
- Bare Android app with an accessibility service scoped to Hinge.
- Dumps the node tree of each Hinge screen to a log file and takes a screenshot.
- Browse Hinge by hand for a day with it enabled.

**Exit:** you know, for each screen, whether the profile text, the like and skip buttons, the comment box, and the send button can be found from the node tree or need fixed screen positions. You also know the account shows no warnings with the service enabled.

## Phase 1 — Control harness (4–6 days)
- `ScreenStateMachine`, `Selectors` (rules in `assets/selectors.json`), `GestureEngine`, `ScreenReader`.
- Status bubble with stop, volume-down stop, keep-screen-on, foreground notification.
- Actions: scroll profile, skip, like target N, open comment sheet, type, send. Each one has a verify step.

**Exit:** the app walks through 20 profiles, capturing each one fully and **skipping all of them**, with zero `UNKNOWN` states. Stop works instantly from the bubble and from volume-down.

## Phase 2 — Brain + shadow mode (1 week)
- Proxy (Cloudflare Worker) with key, device token, daily $ cap.
- `BrainClient` with structured output, prompt caching, cost tracking.
- Fill in `preferences.json`, `facts.json`, `voice_samples.md`.
- Shadow mode: you swipe by hand, the bubble shows the agent's verdict, your action is recorded as a label.
- Export button + `evals/run_eval.py`.

**Exit:** 100+ labels, ≥ 85% like-precision on a held-out 30%, cost per profile within 2× of [COST.md](COST.md).

## Phase 3 — Approval mode (1–2 weeks)
- Overlay approval card: ✅ Send · ✏️ Edit · ❤️ Like w/o comment · ⏭ Skip.
- Opener validation (`Validate.kt`).

**Exit:** ≥ 100 approvals, < 10% of comments edited, < 10% of likes overridden, no account warnings or drop in match rate.

## Phase 4 — Auto mode with caps (ongoing)
- High confidence → auto. Medium → approval card if you're watching, otherwise skip. Low → skip.
- End-of-session summary notification. Weekly few-shot refresh + eval.

**Exit:** two weeks of sessions finishing without you having to step in, and match rate at least as good as your manual baseline.

## Phase 5 — Inbox & openers
- `InboxRunner` runs at the end of each session: finds new matches with no message yet, replies to their like-comment or sends an opener (approval first, then auto), marks handoff.

**Exit:** every new match gets an opener by the end of the next session, with edit rate < 10%.

## Later / maybe
- Opus vs Sonnet vs Haiku-cascade eval to tune cost.
- Stats screen: match rate by score bucket, which prompt types get replies, for tuning thresholds.
- Auto-detect a Hinge update (version change) and block sessions until the selectors are re-checked in Inspect mode.
