package com.autodate.agent

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autodate.Mode
import com.autodate.data.CaptureStore
import com.autodate.screen.Likeable
import com.autodate.screen.ProfileSnapshot
import com.autodate.screen.Screen
import com.autodate.screen.walk
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Shadow mode: you swipe; this watches. Each profile you like or skip becomes a labeled example,
 * and the AI's own verdict is recorded next to yours so we can measure agreement. Zero taps.
 *
 * Hinge doesn't report taps to accessibility services, so your choice is inferred from what happens
 * on screen: when the profile changes, it was a like if the like sheet opened for it, else a skip.
 */
class ShadowObserver(private val s: AgentService) {

    private val texts = LinkedHashSet<String>()
    private val likeables = mutableListOf<Likeable>()
    private val images: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
    private var currentName: String? = null
    @Volatile private var sheetSeen = false
    @Volatile private var lastComment: String? = null
    private val commentReads = Executors.newSingleThreadExecutor()
    @Volatile private var polling = false
    private var pollStarted = 0L
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var changes = 0
    private val settler = Settler(quietMs = 750, maxWaitMs = 2500) { observe() }

    fun onEvent(e: AccessibilityEvent) {
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            -> {
                changes++
                s.overlay.setSeen(false)
                settler.poke()
            }
        }
    }

    /**
     * Keeps the latest text from the comment box while the like sheet is open. The box isn't in the
     * accessibility tree, so this asks the keyboard's input connection, off the main thread. Hinge
     * sends no event per keystroke, so reading only on screen events missed the last words typed
     * before Send. Instead, the sheet starts a steady poll that runs until the profile changes.
     * Reads go through one thread, so a slow read can't overwrite a newer one.
     */
    private fun readComment() {
        if (!sheetSeen) return
        commentReads.execute {
            s.inputFieldText()?.trim()?.takeIf { it.isNotEmpty() }?.let { lastComment = it }
        }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        pollStarted = System.currentTimeMillis()
        main.post(poll)
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!sheetSeen || System.currentTimeMillis() - pollStarted > POLL_MAX_MS) {
                polling = false
                return
            }
            readComment()
            main.postDelayed(this, POLL_MS)
        }
    }

    /** Bubble tap in shadow mode: "what would you do with this one?" */
    fun askNow() {
        observe()
        val snap = snapshot()
        if (texts.isEmpty()) {
            s.overlay.flash("Nothing to judge yet. Open a profile on Discover.")
            return
        }
        s.overlay.flash("Asking AI…", 20_000)
        thread {
            val msg = runCatching {
                val dec = s.brain.judge(snap, s.examples(), s.db.commentEdits(s.config.shadowCommentsSince))
                buildString {
                    append(if (dec.like) "AI: like" else "AI: skip").append(" (${dec.score})\n")
                    dec.reasons.take(2).forEach { append("• ").append(it).append('\n') }
                    dec.comment?.let { append("“").append(it).append("”") }
                }
            }.getOrElse { "AI error: ${it.message}" }
            s.overlay.flash(msg.trim(), 10_000)
        }
    }

    private fun observe() {
        s.overlay.setSeen(null) // stays neutral off Discover; accumulate turns it green on a profile
        val root = s.reader.root() ?: return
        when (s.reader.classify(root)) {
            Screen.COMMENT_SHEET -> {
                sheetSeen = true
                startPolling()
            }
            Screen.DISCOVER -> {
                val name = s.reader.currentName(root) ?: return
                if (name != currentName) {
                    if (currentName != null) finish(if (sheetSeen) "like" else "skip", lastComment)
                    currentName = name
                    sheetSeen = false
                    lastComment = null
                }
                accumulate(root)
            }
            else -> if (root.walk().any { it.isEditable }) { // an unrecognized like sheet
                sheetSeen = true
                startPolling()
            }
        }
    }

    private fun accumulate(root: AccessibilityNodeInfo) {
        texts += s.reader.profileTexts(root)
        // Without our own scrolling we can't track offsets, so merge by label instead.
        val found = mutableListOf<Likeable>()
        s.reader.collectLikeables(root, found, 0)
        for (l in found) {
            val photos = likeables.count { it.kind == "photo" }
            val dup = if (l.kind == "photo") photos >= 6 else likeables.any { it.label == l.label }
            if (!dup) likeables += l.copy(index = likeables.size, label = if (l.kind == "photo") "photo ${photos + 1}" else l.label)
        }
        s.overlay.setSeen(false)
        if (images.size < 6) shoot(changes, SHOT_TRIES) else s.overlay.setSeen(true)
    }

    /**
     * Saves a screenshot, then turns the bubble green. Screenshots are rate limited to one every
     * 1.1 s, so a quick read has to retry. If the screen moves meanwhile, the next read takes over.
     */
    private fun shoot(seenAt: Int, tries: Int) {
        if (seenAt != changes) return
        s.shots.take { bmp, _ ->
            when {
                bmp != null -> {
                    images += jpeg(bmp)
                    if (seenAt == changes) s.overlay.setSeen(true)
                }
                tries > 0 -> main.postDelayed({ shoot(seenAt, tries - 1) }, SHOT_RETRY_MS)
                seenAt == changes -> s.overlay.setSeen(true) // the text was still read
            }
        }
    }

    private fun finish(verdict: String, comment: String?) {
        val snap = snapshot()
        texts.clear()
        likeables.clear()
        images.clear()
        if (snap.text.isBlank()) return
        thread {
            s.db.upsertProfile(snap.key, snap.name, snap.text, snap.likeablesJson())
            s.db.insertLabel(snap.key, "shadow_manual", verdict, snap.text, comment?.let { "your comment: $it" })
            CaptureStore.saveProfileImages(s, snap.key, snap.images)
            if (!s.config.brainConfigured) {
                s.overlay.flash("Saved: ${snap.name} → $verdict (${s.db.labelCount()} labels)", RESULT_MS)
                return@thread
            }
            val msg = runCatching {
                val dec = s.brain.judge(snap, s.examples(), s.db.commentEdits(s.config.shadowCommentsSince))
                dec.looksNote()?.let { s.db.appendLatestLabelNote(snap.key, it) }
                val id = s.db.insertDecision(snap.key, Mode.SHADOW.name, dec.raw)
                val aiLike = dec.like && dec.score >= s.config.likeThreshold
                val agrees = aiLike == (verdict == "like")
                s.db.finishDecision(id, verdict, comment, !agrees)
                "${snap.name}: you $verdict · AI ${if (aiLike) "like" else "skip"} ${dec.score} ${if (agrees) "✓" else "✗"}"
            }.getOrElse { "Saved: ${snap.name} → $verdict (AI error: ${it.message})" }
            s.overlay.flash("$msg\n${s.db.labelCount()} labels", RESULT_MS)
        }
    }

    private fun snapshot(): ProfileSnapshot {
        val list = texts.toList()
        val name = currentName.orEmpty()
        return ProfileSnapshot(ProfileSnapshot.keyFor(listOf(name) + list), name, "Name: $name\n" + list.joinToString("\n"), images.toList(), likeables.toList())
    }

    private fun jpeg(bmp: Bitmap): ByteArray {
        val scaled = Bitmap.createScaledBitmap(bmp, 540, bmp.height * 540 / bmp.width, true)
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
    }

    fun cancel() {
        settler.cancel()
        main.removeCallbacks(poll)
        polling = false
        s.overlay.setSeen(null)
    }

    private companion object {
        const val SHOT_TRIES = 4
        const val SHOT_RETRY_MS = 300L

        /** How often the comment box is read while the like sheet is open, and for how long at most. */
        const val POLL_MS = 250L
        const val POLL_MAX_MS = 5 * 60_000L

        /** Long enough to read the result after you've already moved on to the next profile. */
        const val RESULT_MS = 8_000L
    }
}
