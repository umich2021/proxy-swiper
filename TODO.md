# TODO

Your checklist, in order. Phase details and exit criteria are in [ROADMAP.md](ROADMAP.md).

_Last updated Nov 9, 2026._

## 1. Capture the remaining Hinge screens (finishes Phase 0)

In autoDate pick **Inspect**, open Hinge yourself (no "Start session"), go to each screen, and tap the REC bubble. Then **Export** and copy the zip into this folder.

Results from `autodate_20261029_071336.zip`:

| Screen | Status | Notes |
|---|---|---|
| Discover (like / skip / tabs) | ✅ Works | All rules matched |
| Like sheet: **Send like** button | ✅ Works | `send_like` matched |
| Like sheet: comment box | ❌ Recapture | The sheet shows an "Edit comment" placeholder, not a text field. **Tap "Edit comment" so the keyboard opens, then tap the bubble.** Don't send |
| Matches list | ⏳ Not possible yet | Shows "You're new, no matches yet". Capture it once you have a match |
| Chat (message box, send) | ⏳ Not possible yet | Needs a match. Open the chat, tap the message box, tap the bubble. Don't send |
| Out of likes | ⏳ Not seen yet | Shows up after you've used today's likes. Tap the bubble when it appears |

- [ ] Recapture the comment box (above)
- [ ] Capture Matches list + chat after your first match
- [ ] Capture the out-of-likes screen
- [ ] Confirm the account shows no warnings with the accessibility service on
- [ ] Hand the new zip to Claude to update `android/app/src/main/assets/selectors.json`

## 2. Fill in your details (the templates) ✅ done Oct 29

Raw preference notes are kept in `proxy/config/preferences_notes.md` (not loaded).

All three files are in `proxy/config/`. The AI only uses what's in them. Restart the proxy after editing any of them.

- [x] **`facts.json`**: true facts about you: name, age, job, neighborhood, hobbies, pets, favorites. Openers can only mention things from this file, so leave out anything you don't want said.
- [x] **`preferences.json`**: rewrite `about_me_summary` (delete "TEMPLATE - replace:"). Then edit `looking_for`:
  - `must_have`, `strong_plus`, `mild_plus`, `neutral`, `mild_minus`
  - `never`: hard no's. Keep these about behavior and intent, not looks.
  - `thresholds`: `like: 70`, `ask_me_below: 55`, moved up from 65 on Nov 1 after reviewing Shadow and Approve history
- [x] **`voice_samples.md`**: replace the 3 example lines with 10–20 real first messages or texts you've sent, exactly as you typed them (lowercase, punctuation, emoji and all)

See [PREFERENCES.md](PREFERENCES.md) for how these are used.

## 3. Phase 1 test run (AI drives, skips everything) ✅ passed Oct 30

- [x] Run the app through 20 profiles, skipping all of them
- [x] Zero "unknown screen" stops
- [x] Bubble tap stops it instantly
- [x] Volume-down stops it instantly

Follow-ups from the test (app changes are built; install the new APK):
- [ ] Liking from the approval card couldn't find the heart. A fix is in the app (it scrolls back to the top first). Retest when you first approve a like. If it still fails, give Claude the bubble message and an Inspect capture of the like sheet with "Edit comment" tapped, so the rules in `selectors.json` can be fixed. `comment_field` there is still a guess
- [x] Redeploy the proxy so the stricter comment-writing rules go live (done Oct 30)
- [ ] Turn on auto-deploy (see `HANDOFF.md`). Until then, the Oct 30 preference changes (vegan/vegetarian, age, SF, basics skip-list) aren't live
- Approval card: **Hide** lets you scroll the profile, tap the title to bring it back
- Bubble tap while idle starts or resumes a session
- Bubble shows `≈$x API`: on the subscription you pay $0, this is what the same calls would cost on the API

## 4. Proxy ✅ done Oct 30

- [x] Run it without your PC on: live on the personal server, using the Claude subscription (details in `SERVER-SETUP.md`, not in git)
- [x] App connected: URL + device token saved in autoDate (the token lives on the server, not in git)
- Auto-deploy: pushing a change under `proxy/` to `main` deploys it to the server. `proxy/config/` is no longer in git, so copy it up by hand after editing it (command in `SERVER-SETUP.md`)
- Backup only: run it on a PC with `cd proxy && npm run local`, then connect the phone by USB (`adb reverse tcp:8787 tcp:8787`, URL `http://localhost:8787`) or Wi-Fi (`http://<PC LAN IP>:8787`)

## 5. Shadow mode (Phase 2)

- [ ] Install the latest APK (History tab, Open Hinge in every mode)
- [x] One-time, phone on USB: `adb shell pm grant com.autodate.app android.permission.WRITE_SECURE_SETTINGS` so accessibility turns itself back on after updates (done Oct 30; redo only after an uninstall)
- [ ] Switch to **Shadow** and swipe by hand as usual for a few days
- Track progress on the **History** tab: agreement %, like-precision, and every AI call (filter to disagreements)
- [ ] Get to 100+ labels (skips count too, keep swiping after your likes run out). Pause ~1s per scroll so it captures each screen
- [ ] Use **Review** to rate your private physical attraction on the saved profiles offline (1–10 or Face unclear), with optional type notes. This is separate from the original Hinge swipe
- [ ] Check History: "When the AI says like, you liked too" ≥ 85%?
- [ ] Settings → **Export data to Downloads**, copy the zip into this folder, and give it to Claude to run `evals/run_eval.py` (held-out 30%) and read the disagreements
- [ ] If under 85%: Claude tunes `preferences.json` (push = live), then do another 30–50 Shadow swipes and recheck
- [ ] Goal: like-precision ≥ 85% and cost per profile within 2× of [COST.md](COST.md)

## 6. Before Approve mode: fix liking

- [x] Oct 30: Send in Approve skipped instead of liking. Fixed: more ways to find the heart (by prompt text, then first photo as a last resort), plain "Like" hearts recognized, comment typed after tapping "Edit comment"
- [x] Oct 31: comment typing stopped the session ("couldn't type into the text field"). Fixed: types only into an editable field, and the bubble no longer blocks the app's own taps
- [x] Oct 31: hearts work. Comments didn't: Hinge's box isn't exposed as a text field, so likes went out blank. Now typed through the keyboard's input connection and read back before sending
- [ ] Install the new build, run Approve, press Send on one with a comment. Watch the box fill in. History should say "Sent with comment". If the bubble says "Couldn't type the comment", tell Claude with the phone plugged in

## 7. Approve mode (Phase 3)

- [ ] Switch to **Approve** → Start session. The AI swipes; each like shows a card: Send / Edit / Like only / Skip / Hide
- [ ] First real like: confirm the heart and comment actually send (this verifies the like-button fix)
- [ ] Goal: ~100 approvals, < 10% of comments edited, < 10% of likes overridden, no account warnings or drop in match rate

## 8. Auto mode (Phase 4)

- [ ] Switch to **Auto** only after Approve looks good. Runs within your caps; bubble or volume-down stops it, bubble tap resumes
- [ ] Goal: two weeks of sessions without stepping in, match rate at least as good as when you swiped by hand

## Decisions to make (any time)

- [ ] Hinge+ or free? Free is ~8 likes/day, which is enough for testing
- [ ] Can openers ever go out without your approval? Suggested: only after ~100 approved drafts with <10% edits
- [ ] Mention an AI assistant in your bio? See [RISKS.md](RISKS.md#3-ethics)

## Housekeeping

- [ ] Delete `autodate_20261029_071216.zip`. It's an earlier export from the same session, and the `_071336` zip has everything in it
- [ ] Keep capture zips off cloud sync and out of any repo. They contain other people's profiles
