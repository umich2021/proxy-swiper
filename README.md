# autoDate — On-Phone Hinge Agent

An Android app you install on your own phone. You open it, tap **Start**, and it runs Hinge for you: it reads each profile, decides like or skip based on your preferences, picks the photo or prompt to react to, and writes the first message. Once a match replies, you take over the conversation.

It uses Android's **Accessibility Service** (the same system screen readers and auto-clickers use) to read Hinge's screen and perform taps and swipes. Claude makes the decisions. A tiny cloud proxy holds the API key.

> ⚠️ **Read [RISKS.md](RISKS.md) first.** Hinge's Terms of Service prohibit automated use. The worst realistic outcome is a permanent ban of your real account and phone number.

## Set up your own copy

Everyone runs their own proxy, with their own Claude access, preferences, and device token. Nothing here is tied to my account.

1. **Proxy.** In `proxy/`, run `npm install` then `npm run local`. That runs it on your PC through Claude Code on your own Claude subscription. You can also put it on Cloudflare with an API key, or on your own server ([proxy/README.md](proxy/README.md)). The first run copies the templates from `proxy/config.example/` into `proxy/config/`. Fill in `preferences.json`, `facts.json`, and `voice_samples.md` with your stuff (git ignores them). `npm run local` prints your device token.
2. **App.** Open `android/` in Android Studio and hit Run with your phone plugged in, or build it from the command line ([android/README.md](android/README.md)). Needs Android 11+.
3. **Phone.** The app opens a setup guide that walks you through it: allowing restricted settings (Android 13+ blocks accessibility for sideloaded apps until you do), turning on the accessibility service, and pasting your proxy URL and token.
4. Start in **Learn** mode and swipe by hand for a few days so it learns your taste. Then try Co-pilot.

## Docs

| File | What's in it |
|---|---|
| [TECH.md](TECH.md) | App architecture, accessibility layer, gestures, LLM pipeline, proxy, data model |
| [COST.md](COST.md) | Monthly costs, LLM token math |
| [PREFERENCES.md](PREFERENCES.md) | How preferences are expressed, scored, and learned; opener-writing rules |
| [ROADMAP.md](ROADMAP.md) | Phased build plan with exit criteria |
| [RISKS.md](RISKS.md) | ToS, detection, ethics, privacy, prompt injection, phone-safety failure modes |

## How it feels to use

1. Plug the phone in, open **autoDate**, tap **Start**.
2. Hinge opens. A small floating bubble shows status ("Profile 14 · 5/8 likes · $0.31") and works as the stop button. Drag it to move it.
3. The agent scrolls each profile, pauses for a human-like reading time, then skips it or likes a specific photo or prompt with a comment.
4. It stops when the daily limit is reached, the session timer runs out, or you hit the bubble or press **volume-down**.
5. New matches get an opener (or a reply to their like-comment), and you get a notification to take over.

**It takes over the screen while running.** Android can only tap what's actually on screen, so Hinge must stay in the foreground. Run it when you aren't using your phone.

## Goals

1. **Swipe on preferences.** Like or skip, with a short written reason you can audit.
2. **Pick what to like.** Hinge likes target a specific photo or prompt. The agent chooses the best one.
3. **Send the first message.** A short, specific comment on the like, or an opener after a match.
4. **Hand off.** You run every conversation after the first message.
5. **Learn your taste.** When you swipe by hand in shadow mode, and when you approve or override the agent, those choices become training examples.

## Non-goals (v1)

- Holding conversations past the first message
- Running while you use other apps (not possible on one phone)
- iPhone support (iOS doesn't allow it)
- Play Store distribution (sideload only)

## Key decisions

| Decision | Choice | Why |
|---|---|---|
| Device | Your real Android phone | Passes device-integrity checks, normal location, no emulator rabbit hole, and no one-login-at-a-time problem |
| Control | `AccessibilityService`: read view tree, `dispatchGesture`, `takeScreenshot` | No root, no USB debugging, no PC |
| Language | Kotlin, minSdk 30 (Android 11+) | `takeScreenshot()` needs API 30 |
| Brain | Claude Opus 5.5 (`claude-opus-5-5`), optional Claude Haiku 5.5 pre-filter | Strong vision and judgment. The pre-filter roughly halves cost |
| API key | Small proxy (Cloudflare Worker) | Key stays off the phone. Enforces a daily $ cap server-side |
| Approvals | On-screen overlay card with ✅ / ✏️ / ⏭ | You're looking at the phone anyway in approval mode |
| Storage | SQLite on the phone | Single user, private |
| Rollout | shadow → approve → auto | Catch bad behavior before it costs you the account |

## Open questions (you decide)

1. **Hinge+ or free?** Free gives ~8 likes/day, which is enough to build and test. See [COST.md](COST.md).
2. **Autonomy ceiling.** Should openers ever go out without your approval? The recommendation is yes, only after ~100 approved drafts with <10% edits.
3. **Disclosure.** Should your bio mention an AI assistant? See [RISKS.md](RISKS.md#3-ethics).
4. **Phone model / Android version.** Some brands (Samsung, Xiaomi) aggressively kill background services. TECH.md §9 covers the settings to change.
