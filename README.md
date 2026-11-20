# autoDate

An Android app that swipes Hinge for you. It reads each profile, decides like or skip based on what you're looking for, picks the photo or prompt to like, and writes a short comment that sounds like you. Once someone replies, you take over.

<table>
  <tr>
    <td><img src="docs/screenshots/landing.png" width="200" alt="Landing page"></td>
    <td><img src="docs/screenshots/how-it-works.png" width="200" alt="How it works"></td>
    <td><img src="docs/screenshots/accessibility.png" width="200" alt="Accessibility setup"></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/mode.png" width="200" alt="Choosing a mode"></td>
    <td><img src="docs/screenshots/done.png" width="200" alt="Setup done"></td>
    <td><img src="docs/screenshots/home.png" width="200" alt="Home screen"></td>
  </tr>
</table>

It runs on your own phone. Android's accessibility service (the same thing screen readers and auto-clickers use) lets it read Hinge's screen and tap. Claude makes the calls. A small proxy you run yourself holds your Claude access and your preferences, so none of that lives on the phone.

Hinge's terms don't allow automation, so the worst case is a permanent ban on your account and phone number. More in [RISKS.md](RISKS.md).

## Set up your own copy

Everyone runs their own proxy, with their own Claude access, preferences, and device token. Nothing here is tied to my account.

1. **Proxy.** In `proxy/`, run `npm install` then `npm run local`. That runs it on your PC through Claude Code on your own Claude subscription. You can also put it on Cloudflare with an API key, or on your own server ([proxy/README.md](proxy/README.md)). The first run copies the templates from `proxy/config.example/` into `proxy/config/`. Fill in `preferences.json`, `facts.json`, and `voice_samples.md` with your stuff (git ignores them). `npm run local` prints your device token.
2. **App.** Open `android/` in Android Studio and hit Run with your phone plugged in, or build it from the command line ([android/README.md](android/README.md)). Needs Android 11+.
3. **Phone.** The app opens a setup guide that walks you through it: allowing restricted settings (Android 13+ blocks accessibility for sideloaded apps until you do), turning on the accessibility service, and pasting your proxy URL and token.
4. Start in **Learn** mode and swipe by hand for a few days so it learns your taste. Then try Co-pilot.

## Using it

Pick a mode on Home and tap the big button:

| Mode | What happens |
|---|---|
| Learn | You swipe like normal. It watches, guesses, and learns from what you actually do. Nothing gets sent for you |
| Co-pilot | It swipes. Before each like you see its comment and tap send, edit, or skip |
| Autopilot | It swipes and sends on its own, within your daily limits |
| Record | Debug only. Saves Hinge's screens when you tap the REC bubble, for fixing button matching |

While it runs, a floating bubble shows where it's at ("Profile 14 · 5/8 likes · $0.31"). Tap it or press volume-down to stop. It also stops by itself at the daily like cap, when the session timer runs out, or when Hinge says you're out of likes. New matches get an opener and you get a notification to take it from there.

It takes over the screen while it runs (Android can only tap what's actually on screen), so run it when you're not using your phone. Charging on the nightstand works.

Good to know:
- Free Hinge gives you about 8 likes a day. That's plenty to start. [COST.md](COST.md) has the Hinge+ and Claude cost math
- Samsung and Xiaomi like to kill background services. [TECH.md](TECH.md) §9 has the settings to change

## What it won't do

- Hold conversations past the first message. That part's on you
- Run while you use other apps (one phone, one screen)
- iPhone. iOS doesn't allow this kind of app
- Play Store. It's sideload only

## Why it's built this way

| Decision | Choice | Why |
|---|---|---|
| Device | Your real Android phone | Passes device integrity checks, normal location, no emulator rabbit hole, no one-login-at-a-time problem |
| Control | `AccessibilityService`: read the view tree, `dispatchGesture`, `takeScreenshot` | No root, no USB debugging, no PC needed once it's set up |
| Language | Kotlin, minSdk 30 (Android 11+) | `takeScreenshot()` needs API 30 |
| Brain | Claude Opus 5.5 (`claude-opus-5-5`), optional Claude Haiku 5.5 pre-filter | Good at reading photos and judging. The pre-filter roughly halves the cost |
| Keys | A small proxy you run | Your key stays off the phone, and it enforces a daily $ cap on the server side |
| Approvals | A card drawn over Hinge with send / edit / skip | You're looking at the phone anyway in Co-pilot |
| Storage | SQLite on the phone | One user, stays private |
| Rollout | Learn → Co-pilot → Autopilot | Catch bad behavior before it costs you the account |

## Docs

| File | What's in it |
|---|---|
| [TECH.md](TECH.md) | App architecture, accessibility layer, gestures, LLM pipeline, proxy, data model |
| [COST.md](COST.md) | Monthly costs, token math |
| [PREFERENCES.md](PREFERENCES.md) | How preferences are written, scored, and learned, plus the comment-writing rules |
| [ROADMAP.md](ROADMAP.md) | Phased build plan with exit criteria |
| [RISKS.md](RISKS.md) | ToS, detection, ethics, privacy, prompt injection, things that can go wrong on the phone |

## License

MIT. See [LICENSE](LICENSE).
