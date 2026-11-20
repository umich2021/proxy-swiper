# autoDate Android app

One accessibility service with four modes: **Learn** (`SHADOW` in code), **Co-pilot** (`APPROVE`), **Autopilot** (`AUTO`) and **Record** (`INSPECT`). It defaults to Learn, which never taps anything.

## Build & install

**Android Studio (Windows):** open this `android/` folder, wait for Gradle sync, plug in your phone with USB debugging on, and press Run.

**Command line (WSL):**
```sh
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2 ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk → copy to phone and open, or `adb install` it
```

## First-time setup

The app opens a setup guide on first launch, with animations of each tap. It covers the steps below. To see it again, go to Settings → App → Replay the setup guide.

1. **Android 13+:** Settings → Apps → autoDate → ⋮ → **Allow restricted settings**. The menu item only shows up after you've tried to turn on the accessibility service once.
2. Accessibility → autoDate → **Use autoDate** → Allow.
3. Run your own proxy ([../proxy/README.md](../proxy/README.md)), then paste its URL and `DEVICE_TOKEN` on the guide's Connect step.

Optional: `adb shell pm grant com.autodate.app android.permission.WRITE_SECURE_SETTINGS` lets the app turn accessibility back on by itself after updates.

## Suggested order

| Step | Mode | What you do | What you get |
|---|---|---|---|
| 1 | **Inspect** | Browse Hinge for a day. Tap the REC bubble on screens it misses | Export → `python3 ../tools/summarize_dumps.py autodate_*.zip` shows what Hinge exposes and which buttons matched |
| 2 | **Shadow** | Swipe as usual for a few days. Your like/skip is detected when the profile changes. Tap the bubble to ask the AI about the profile on screen | Labels from your real choices + agreement shown after each one |
| 3 | **Review tab** | Rate only your private physical attraction on saved screenshots (1–10 or unclear), with optional type notes | Physical-type labels without Hinge or API calls |
| 4 | — | Export → `python3 ../evals/run_eval.py autodate_*.zip --proxy … --token …` | Agreement / like-precision, with private physical labels included. Aim for ≥ 85% precision |
| 5 | **Approve** | **Start session**. Approve, edit or skip each like on the card | The AI drives. Every like is your call. Openers for new matches at the end |
| 6 | **Auto** | **Start session** | Runs within your caps (likes/day, profiles/session, minutes) |

## Stopping a session

Tap the bubble, press **volume-down**, or touch the screen (a touch cancels the agent's gesture). It also stops by itself on: the daily like cap, Hinge's "out of likes" screen, the session timer or profile cap, the proxy's daily $ cap, Hinge leaving the foreground, an unknown screen (it saves a capture for debugging first), or a like or skip it couldn't confirm.

## When something breaks

- **"can't find the skip button" / "unknown screen":** capture that screen in Inspect mode and send the export so `assets/selectors.json` can be fixed.
- **Hinge updated:** button ids may have changed. Re-run Inspect on the changed screens.
- **Bubble missing:** the accessibility service got turned off (some phones do this after updates). Turn it back on.
