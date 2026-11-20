package com.autodate.agent

import android.view.accessibility.AccessibilityNodeInfo
import com.autodate.Mode
import com.autodate.brain.BrainError
import com.autodate.brain.Decision
import com.autodate.data.CaptureStore
import com.autodate.data.Db
import com.autodate.screen.Likeable
import com.autodate.screen.ProfileSnapshot
import com.autodate.screen.Screen
import com.autodate.screen.walk
import com.autodate.ui.Approval
import org.json.JSONArray
import java.io.IOException
import kotlin.math.ceil

/**
 * One swiping session (approve or auto mode), on its own thread:
 * read profile → ask the brain → (ask you) → wait → like or skip → repeat, until a cap or stop.
 */
class SessionController(private val s: AgentService) {

    private val mode = s.config.mode
    private val control = s.control
    private val d = s.driver

    private val startedAt = System.currentTimeMillis()
    private var seen = 0
    private var likes = 0
    private var errors = 0
    private var cost = 0.0
    private var listCost = 0.0
    private var brainErrors = 0
    private var unknownScreens = 0
    private var reopens = 0 // times Hinge was brought back after something closed it
    private var sentComment: String? = null // what actually went out with the last like
    private var mayBeScrolled = true // the profile on screen may not be at its top (first of a session, or after a mix-up)
    private val likesLeftAtStart = (s.config.dailyLikeCap - s.db.likesToday()).coerceAtLeast(0)

    fun run() {
        val sessionId = s.db.startSession(mode.name)
        var reason = "finished"
        try {
            refreshThresholds()
            val examples = s.examples()
            d.awaitHinge()
            d.pause(1500, 3000)
            loop(examples)
        } catch (e: Stopped) {
            reason = e.reason
        } catch (e: Exception) {
            reason = "error: ${e.message}"
            errors++
        }

        // A session that ended on its own (not stopped by you or an error) checks new matches.
        var openers = 0
        if (control.stopReason == null && !reason.startsWith("error") && s.config.runInbox) {
            try {
                openers = s.inbox.run()
            } catch (e: Stopped) {
                reason += "; inbox: ${e.reason}"
            } catch (e: Exception) {
                reason += "; inbox error: ${e.message}"
            }
        }

        s.db.endSession(sessionId, seen, likes, errors, cost, reason)
        s.overlay.flash("Session ended: $seen profiles, $likes likes, $openers openers, ${costText()}. $reason", 6000)
    }

    /** "$0.12", or "$0.00 paid (≈$0.31 at API price)" on the subscription backend, where nothing is billed per call. */
    private fun costText(): String =
        if (listCost > cost + 0.005) "\$%.2f paid (≈\$%.2f at API price)".format(cost, listCost) else "\$%.2f".format(cost)

    private fun loop(examples: JSONArray) {
        while (true) {
            control.check()
            if (System.currentTimeMillis() - startedAt > s.config.sessionMinutes * 60_000L) throw Stopped("session time limit")
            if (seen >= s.config.sessionProfileCap) throw Stopped("profile limit for this session")
            if (s.db.likesToday() >= s.config.dailyLikeCap) throw Stopped("daily like limit reached")
            status()
            if (s.reader.root() == null) recoverHinge()
            when (s.reader.classify()) {
                Screen.POPUP -> {
                    d.tap("dismiss")
                    d.pause(800, 1600)
                }
                Screen.OUT_OF_LIKES -> throw Stopped("Hinge says you're out of likes")
                Screen.COMMENT_SHEET -> {
                    closeLikeSheet()
                    d.pause(600, 1200)
                }
                Screen.DISCOVER -> {
                    unknownScreens = 0
                    handleProfile(examples)
                    seen++
                }
                else -> {
                    if (unknownScreens == 0) d.tap("discover_tab") // Hinge may open on another tab
                    if (++unknownScreens >= 3) {
                        s.inspector.capture("unknown_screen")
                        throw Stopped("unknown screen (saved a capture for debugging)")
                    }
                    control.sleep(Jitter.logNormal(1500.0))
                }
            }
        }
    }

    private fun handleProfile(examples: JSONArray) {
        val snap = s.reader.captureProfile(s.gestures, control, s.shots, fromTop = mayBeScrolled)
        mayBeScrolled = false
        s.db.upsertProfile(snap.key, snap.name, snap.text, snap.likeablesJson())

        // Back after a like you approved didn't go out (it failed, or the session stopped): send that, no new verdict.
        s.db.pendingLike(snap.key, PENDING_MAX_AGE_MS)?.let { p ->
            s.overlay.flash("${snap.name}: you already approved this one, sending it", 2500)
            val target = snap.likeables.firstOrNull { it.label == p.targetLabel } ?: snap.likeables.firstOrNull { it.index == p.target }
            deliver(snap, p.decisionId, target, p.comment, approved = true, aiLike = p.aiLike, aiComment = p.aiComment, score = null)
            return
        }

        val decision = judgeOrWait(snap, examples)
        if (decision == null) {
            if (!skip(snap)) s.overlay.flash("${snap.name} isn't on screen any more, moving on", 2500)
            return
        }
        cost += decision.costUsd
        listCost += decision.listUsd
        val decisionId = s.db.insertDecision(snap.key, mode.name, decision.raw)

        var action = policy(decision)
        // The AI said like at or over your cutoff but isn't sure: in approve mode that's yours to call, so
        // it shows the card even with "Ask me when unsure" off (that switch is about scores under the cutoff).
        val unsureLike = decision.like && decision.score >= s.config.likeThreshold
        if (action == Action.ASK && (mode == Mode.AUTO || (!s.config.askWhenUnsure && !unsureLike))) action = Action.SKIP
        var comment = decision.comment
        var target = snap.likeables.firstOrNull { it.index == decision.targetIndex } ?: snap.likeables.firstOrNull()
        var aiComment: String? = null // set when you sent an AI rewrite for an item you picked

        if (mode == Mode.APPROVE && action != Action.SKIP) {
            val choice = s.overlay.askApproval(
                title = "${if (action == Action.LIKE) "Like?" else "Unsure"} · ${decision.score}/100 · ${snap.name}" +
                    (decision.goal?.let { " · $it" } ?: ""),
                body = decision.reasons.joinToString("\n") { "• $it" },
                comment = comment,
                allowLikeOnly = true,
                sendLabel = "Send",
                timeoutMs = 300_000, // room to hide the card and scroll the profile first
                targets = snap.likeables,
                target = target?.index,
                rewrite = { i -> rewriteComment(snap, i) },
            )
            control.check() // stopped while the card was up: nothing below touches Hinge or your labels
            val before = action
            when (choice) {
                is Approval.Send -> { action = Action.LIKE; comment = choice.comment; aiComment = choice.aiComment; target = pickTarget(snap, choice.target, target) }
                is Approval.LikeOnly -> { action = Action.LIKE; comment = null; target = pickTarget(snap, choice.target, target) }
                Approval.Skip -> action = Action.SKIP
            }
            val verdict = if (action == Action.LIKE) "like" else "skip"
            val agreed = (before == Action.LIKE) == (action == Action.LIKE)
            s.db.insertLabel(snap.key, if (agreed) "approval" else "override", verdict, snap.text, decision.looksNote())
            CaptureStore.saveProfileImages(s, snap.key, snap.images)
            if (action == Action.LIKE) {
                s.db.savePendingLike(snap.key, Db.PendingLike(decisionId, comment, aiComment, target?.index, target?.label, decision.like))
            }
            control.sleep(Jitter.wait(300, 700))
        }

        d.pause(500, 1500)
        if (action == Action.SKIP && mode == Mode.AUTO && snap.likeables.isNotEmpty() && Jitter.chance(0.14)) {
            considerThenAbandon(snap)
        }
        val approved = mode == Mode.APPROVE && action == Action.LIKE
        deliver(snap, decisionId, target.takeIf { action == Action.LIKE }, comment, approved, decision.like, aiComment, decision.score)
    }

    /**
     * Likes [target] with [comment], or skips when [target] is null or the like fails. A like you
     * approved gets a second try first. If Hinge has moved on to someone else, does neither and leaves
     * the decision open: an approved like is kept and sent when the profile comes back.
     */
    private fun deliver(
        snap: ProfileSnapshot, decisionId: Long, target: Likeable?, comment: String?,
        approved: Boolean, aiLike: Boolean, aiComment: String?, score: Int?,
    ) {
        var liked = target != null && like(snap, target, comment)
        if (!liked && target != null && approved && whereNow(snap) == Where.ON_PROFILE) {
            s.overlay.flash("Trying ${snap.name}'s like once more", 2000)
            liked = like(snap, target, comment)
        }
        if (!liked && !skip(snap)) {
            s.overlay.flash("${snap.name} isn't on screen any more, moving on", 2500)
            mayBeScrolled = true
            return
        }
        s.db.clearPendingLike(snap.key)

        val final = if (liked) "like" else "skip"
        s.db.finishDecision(
            decisionId, final, if (liked) sentComment else null, final != (if (aiLike) "like" else "skip"),
            finalTarget = if (liked) target?.index else null, aiComment = aiComment,
        )
        if (liked) likes++
        val scoreText = score?.let { " ($it)" }.orEmpty()
        s.overlay.flash(if (liked) "♥ ${snap.name}$scoreText" else "✕ ${snap.name}$scoreText", 1500)

        control.sleep(if (Jitter.chance(0.06)) Jitter.wait(10_000, 40_000) else Jitter.logNormal(1300.0))
    }

    /**
     * Asks the AI about [snap]. When the daily AI budget is used up, says so on the bubble and waits,
     * trying again every half minute, so raising the budget in Settings picks up where it left off.
     * Null when the AI is unavailable for another reason (the profile gets skipped).
     */
    private fun judgeOrWait(snap: ProfileSnapshot, examples: JSONArray): Decision? {
        var waiting = false
        while (true) {
            try {
                return s.brain.judge(snap, examples, s.db.commentEdits(s.config.shadowCommentsSince)).also {
                    brainErrors = 0
                    if (waiting) s.overlay.flash("AI budget raised, carrying on", 2500)
                }
            } catch (e: BrainError) {
                if (e.status != 429) {
                    brainError(e.message)
                    return null
                }
                waiting = true
                s.overlay.setStatus(
                    "Out of AI budget for today (${e.message}).\n" +
                        "Raise it in autoDate → Settings → Daily AI budget and I'll carry on. Tap to stop.",
                )
                control.sleep(30_000)
            } catch (e: IOException) {
                brainError(e.message)
                return null
            }
        }
    }

    private enum class Where { ON_PROFILE, OTHER_PROFILE, LOST }

    /**
     * Gets Hinge back to [snap]'s profile on Discover when something moved it off: a popup, a like
     * sheet left open, or a stray tap onto another tab (seen: your own profile page while a photo was
     * being looked for). Says where it ended up.
     */
    private fun whereNow(snap: ProfileSnapshot): Where {
        repeat(3) {
            val root = s.reader.root() ?: recoverHinge()
            when (s.reader.classify(root)) {
                Screen.DISCOVER -> {
                    val name = s.reader.currentName(root)
                    return if (name == null || name == snap.name) Where.ON_PROFILE else Where.OTHER_PROFILE
                }
                Screen.OUT_OF_LIKES -> throw Stopped("Hinge says you're out of likes")
                Screen.COMMENT_SHEET -> closeLikeSheet()
                Screen.POPUP -> {
                    d.tap("dismiss")
                    d.pause(800, 1600)
                }
                else -> {
                    s.overlay.flash("Hinge moved off ${snap.name}'s profile, going back", 2000)
                    if (!d.tap("discover_tab")) d.back()
                    s.reader.waitFor(control, 4000, Screen.DISCOVER)
                    d.pause(600, 1200)
                }
            }
        }
        return Where.LOST
    }

    private enum class Action { LIKE, SKIP, ASK }

    private fun pickTarget(snap: ProfileSnapshot, chosen: Int?, fallback: Likeable?): Likeable? =
        snap.likeables.firstOrNull { it.index == chosen } ?: fallback

    /** "Rewrite for this one": a new comment for item [index]. Runs on the card's own thread; throws with the reason when there's none. */
    private fun rewriteComment(snap: ProfileSnapshot, index: Int): String {
        val r = s.brain.comment(snap, index, s.db.commentEdits(s.config.shadowCommentsSince))
        synchronized(this) {
            cost += r.costUsd
            listCost += r.listUsd
        }
        return r.text ?: throw Exception(r.problem ?: "no comment came back")
    }

    private fun policy(dec: Decision): Action {
        if (dec.flags.isNotEmpty() || dec.dealbreakers.isNotEmpty()) return Action.SKIP
        if (dec.like && dec.score >= s.config.likeThreshold) {
            if (dec.confidence == "low") return Action.ASK
            // Keep a quarter of today's likes for the second half of the session.
            val firstHalf = seen < s.config.sessionProfileCap / 2
            if (firstHalf && likes >= ceil(likesLeftAtStart * 0.75) && dec.score < 85) return Action.SKIP
            return Action.LIKE
        }
        return if (dec.score >= s.config.askBelow) Action.ASK else Action.SKIP
    }

    /**
     * Likes [target], optionally with a comment. Returns false if anything didn't go as expected;
     * each failure shows why on the bubble and saves a capture, so an export shows what Hinge looked like.
     */
    private fun like(snap: ProfileSnapshot, target: Likeable, comment: String?): Boolean {
        if (whereNow(snap) != Where.ON_PROFILE) return false
        // Open the like sheet on the chosen item, and check it's that item before typing anything.
        // A comment only makes sense on its own item, so there's no "like the first one" fallback with one.
        var attempt = 0
        while (true) {
            val btn = s.reader.findLikeButton(target, snap.likeables, s.gestures, control)
                ?: if (comment != null) return likeFailed("couldn't find ${target.label.take(40)}")
                else s.reader.firstLikeButton(s.gestures, control)?.also { s.overlay.flash("Couldn't find ${target.kind}, liking the first one", 2500) }
                ?: return likeFailed("no like button")
            d.tapNode(btn)
            when (s.reader.waitFor(control, 4000, Screen.COMMENT_SHEET, Screen.OUT_OF_LIKES)) {
                Screen.OUT_OF_LIKES -> throw Stopped("Hinge says you're out of likes")
                Screen.COMMENT_SHEET -> {}
                else -> return likeFailed("like sheet didn't open")
            }
            d.pause(700, 1500)
            if (s.reader.sheetMatches(target) != false) break
            s.inspector.capture("wrong_item")
            if (++attempt >= 2) return likeFailed("opened ${s.reader.sheetItem()?.take(40)} instead of ${target.label.take(40)}")
            s.overlay.flash("Opened the wrong item, trying again", 2000)
            closeLikeSheet()
        }
        if (comment == null && Jitter.chance(0.12)) {
            s.reader.root()?.let { s.selectors.first("comment_field", it) }?.let { box ->
                d.tapNode(box)
                d.pause(700, 1800)
            }
        }
        val commented = comment != null && addComment(comment)
        if (comment != null && !commented) {
            if (!s.config.likeIfCommentFails) {
                s.hideKeyboard()
                return likeFailed("couldn't type the comment")
            }
            s.overlay.flash("Couldn't type the comment, sending the like without it", 3000)
        }
        sentComment = comment.takeIf { commented }
        val before = snap.text.lines().take(4)
        if (!tapSendLike()) return likeFailed("no Send like button")
        val after = s.reader.waitFor(control, 5000, Screen.DISCOVER, Screen.OUT_OF_LIKES, Screen.POPUP)
        if (after == Screen.COMMENT_SHEET) return likeFailed("Send like didn't go through")
        d.awaitNewProfile(before)
        return true
    }

    /**
     * Tapping Hinge's "Edit comment" box opens the keyboard, but the box isn't exposed as a text field
     * (a capture shows no editable node), so the text goes through the input connection the keyboard
     * would use. Returns true only if the text box reads back the comment. Leaves the keyboard open.
     */
    private fun addComment(comment: String): Boolean {
        val box = s.reader.root()?.let { s.selectors.first("comment_field", it) } ?: return commentFailed()
        d.tapNode(box)
        d.pause(700, 1300)
        if (!s.commitText(comment)) return commentFailed()
        d.pause(800, 2000)
        if (!s.inputFieldText().orEmpty().contains(comment)) return commentFailed()
        s.inspector.capture("comment_typed") // a picture of what's about to be sent
        return true
    }

    private fun commentFailed(): Boolean {
        s.inspector.capture("comment_failed")
        return false
    }

    /** The keyboard can cover "Send like"; if it isn't there, closes the keyboard once and looks again. */
    private fun tapSendLike(): Boolean {
        if (d.tap("send_like")) return true
        if (!s.hideKeyboard()) return false
        return s.reader.classify() == Screen.COMMENT_SHEET && d.tap("send_like")
    }

    private fun likeFailed(why: String): Boolean {
        errors++
        s.inspector.capture("like_failed_" + why.replace(Regex("\\W+"), "_"))
        s.overlay.flash("Like failed: $why", 4000)
        control.sleep(Jitter.logNormal(1500.0))
        // If the like sheet is still up, close it so the skip button is reachable.
        s.hideKeyboard()
        closeLikeSheet()
        return false
    }

    /**
     * Opens a like sheet as if considering a comment, sometimes taps the comment box, then backs out.
     * Never sends. Breaks the skip-only / never-open-sheet pattern.
     */
    private fun considerThenAbandon(snap: ProfileSnapshot) {
        val prompts = snap.likeables.filter { it.kind == "prompt" || it.kind == "voice" || it.kind == "poll" }
        val target = prompts.randomOrNull() ?: snap.likeables.drop(1).randomOrNull() ?: snap.likeables.first()
        val btn = s.reader.findLikeButton(target, snap.likeables, s.gestures, control) ?: return
        d.tapNode(btn)
        when (s.reader.waitFor(control, 3500, Screen.COMMENT_SHEET, Screen.OUT_OF_LIKES)) {
            Screen.OUT_OF_LIKES -> throw Stopped("Hinge says you're out of likes")
            Screen.COMMENT_SHEET -> {}
            else -> return
        }
        d.pause(800, 2200)
        if (Jitter.chance(0.45)) peekCommentField()
        closeLikeSheet()
    }

    /** Tap "Edit comment", wait, dismiss the keyboard. Leaves the like sheet up. */
    private fun peekCommentField() {
        val box = s.reader.root()?.let { s.selectors.first("comment_field", it) } ?: return
        d.tapNode(box)
        d.pause(700, 1800)
        val end = System.currentTimeMillis() + 1500 // the keyboard can be slow to come up
        while (!s.keyboardOpen() && System.currentTimeMillis() < end) control.sleep(150)
        s.hideKeyboard()
        d.pause(400, 900)
    }

    private fun closeLikeSheet() {
        repeat(2) {
            if (!sheetStillUp()) return
            if (!d.tap("close_sheet")) d.back()
            // Wait for the sheet to finish closing. Acting again while it animates out sends a second
            // back press to the profile itself, which leaves Hinge.
            if (s.reader.waitFor(control, 3000, Screen.DISCOVER) == Screen.DISCOVER) return
        }
    }

    /** The like sheet is up and staying up: still there after a beat, so it isn't just animating out. */
    private fun sheetStillUp(): Boolean {
        if (s.reader.classify() != Screen.COMMENT_SHEET) return false
        control.sleep(800)
        return s.reader.classify() == Screen.COMMENT_SHEET
    }

    /**
     * Hinge isn't in front. If autoDate's own screen took its place (what a stray Back from Discover
     * does, since Hinge was opened from autoDate), reopens Hinge and returns its screen. Anything else
     * (you switched apps, the phone locked) ends the session as before.
     */
    private fun recoverHinge(): AccessibilityNodeInfo {
        control.check()
        if (!AgentService.appInFront || reopens >= MAX_REOPENS || !s.openHinge()) throw Stopped("Hinge left the foreground")
        reopens++
        s.overlay.flash("Hinge closed, reopening it", 2000)
        d.awaitHinge()
        d.pause(1500, 2500)
        mayBeScrolled = true
        return s.reader.root() ?: throw Stopped("Hinge left the foreground")
    }

    /**
     * Skips [snap], first getting back to it if Hinge moved off. False, without skipping, when someone
     * else is on screen: there's nothing of [snap]'s left to skip, and skipping them unread would be wrong.
     */
    private fun skip(snap: ProfileSnapshot): Boolean {
        when (whereNow(snap)) {
            Where.ON_PROFILE -> {}
            Where.OTHER_PROFILE -> return false
            Where.LOST -> {
                s.inspector.capture("skip_lost")
                throw Stopped("can't get back to Discover to skip (saved a capture)")
            }
        }
        skipCurrent()
        return true
    }

    private fun skipCurrent() {
        val before = d.topTexts()
        if (!d.tap("skip_button")) throw Stopped("can't find the skip button")
        if (!d.awaitNewProfile(before)) {
            if (s.reader.classify() == Screen.OUT_OF_LIKES) throw Stopped("Hinge says you're out of likes")
            errors++
            throw Stopped("skip didn't move to the next profile")
        }
        d.pause(500, 1200)
    }

    private companion object {
        const val MAX_REOPENS = 3

        /** An approved like that didn't go out is sent if the profile shows up again within this long. */
        const val PENDING_MAX_AGE_MS = 3 * 24 * 3_600_000L
    }

    private fun brainError(msg: String?) {
        errors++
        if (++brainErrors >= 2) throw Stopped("AI unavailable: $msg")
    }

    private fun refreshThresholds() {
        runCatching { s.brain.status().optJSONObject("thresholds") }.getOrNull()?.let {
            s.config.likeThreshold = it.optInt("like", s.config.likeThreshold)
            s.config.askBelow = it.optInt("ask_me_below", s.config.askBelow)
        }
    }

    private fun status() {
        val money = if (listCost > cost + 0.005) "≈\$%.2f API".format(listCost) else "\$%.2f".format(cost)
        s.overlay.setStatus("${mode.name.lowercase()} · $seen seen · ${s.db.likesToday()}/${s.config.dailyLikeCap} ♥ · $money\ntap to stop")
    }
}
