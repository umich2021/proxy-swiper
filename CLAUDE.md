# autoDate

Android agent for Hinge + a Claude-backed proxy. Overview in [README.md](README.md), current checklist in [TODO.md](TODO.md).

- **If [HANDOFF.md](HANDOFF.md) exists, read it first.** It's a pending task left from another machine.
- Never commit capture exports (`*.zip`), `proxy/.env.local`, or API keys. Capture zips contain other people's profiles.
- Edits to `proxy/config/*` require restarting the local proxy or redeploying the Worker.
- **Bump the app version in every commit that changes the Android app:** `appVersion` in `android/app/build.gradle.kts`, `MAJOR.MINOR.PATCH`. MAJOR: big updates. MINOR: a small feature or a bug fix (reset PATCH to 0). PATCH: smaller things like UI tweaks. Tell the user the new version number when you push, so they can match it to Settings → App.
