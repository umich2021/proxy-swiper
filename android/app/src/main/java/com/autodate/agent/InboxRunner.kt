package com.autodate.agent

import android.view.accessibility.AccessibilityNodeInfo
import com.autodate.Mode
import com.autodate.brain.BrainError
import com.autodate.screen.ProfileSnapshot
import com.autodate.screen.Screen
import com.autodate.screen.bounds
import com.autodate.screen.clickableAncestor
import com.autodate.screen.label
import com.autodate.screen.walk
import com.autodate.ui.Approval
import com.autodate.ui.Notifier

/**
 * Phase 5: opens Matches, finds matches waiting on a first message ("Your turn"), writes one opener
 * each (or replies to their like-comment), sends it, and hands the conversation to you.
 * It never sends a second message in any chat.
 */
class InboxRunner(private val s: AgentService) {

    private val control = s.control
    private val d = s.driver

    fun run(): Int {
        if (!d.tap("matches_tab")) return 0
        d.pause(1500, 2800)
        var sent = 0
        val tried = mutableSetOf<String>()
        for (attempt in 0 until MAX_PER_RUN) {
            control.check()
            val root = s.reader.root() ?: throw Stopped("Hinge left the foreground")
            val (row, name) = s.selectors.find("match_row_new", root)
                .mapNotNull { label -> rowOf(label)?.let { row -> rowName(row, label)?.let { row to it } } }
                .firstOrNull { (_, name) -> name !in tried && !s.db.openerSentTo(name) }
                ?: break
            tried += name
            if (openChatAndSend(row, name)) sent++
        }
        d.tap("discover_tab")
        d.pause(1000, 2000)
        return sent
    }

    private fun openChatAndSend(row: AccessibilityNodeInfo, name: String): Boolean {
        d.tapNode(row)
        if (s.reader.waitFor(control, 5000, Screen.CHAT) != Screen.CHAT) {
            d.back()
            return false
        }
        d.pause(1200, 2500)
        if (!d.nameNearTop(name)) {
            Notifier.post(s, "autoDate", "Opened a chat but couldn't confirm it was $name; skipped.")
            d.back()
            return false
        }
        val chat = chatLines(name)
        val profile = profileFor(name) ?: run {
            d.back()
            return false
        }

        val opener = try {
            s.brain.opener(profile, chat, s.db.recentOpeners())
        } catch (e: BrainError) {
            if (e.status == 429) throw Stopped("daily AI budget reached")
            null
        }
        var text = opener?.text
        if (text == null) {
            Notifier.post(s, "autoDate", "Couldn't write an opener for $name (${opener?.problem ?: "AI error"}). Your turn.")
            d.back()
            return false
        }

        if (s.config.mode == Mode.APPROVE) {
            val choice = s.overlay.askApproval(
                title = "Opener for $name",
                body = if (chat.isEmpty()) "Chat is empty." else "Chat so far:\n" + chat.takeLast(4).joinToString("\n"),
                comment = text, allowLikeOnly = false, sendLabel = "Send", timeoutMs = 180_000,
            )
            text = (choice as? Approval.Send)?.comment
            control.sleep(Jitter.wait(300, 700))
            if (text == null) {
                d.back()
                return false
            }
        }

        val field = s.reader.root()?.let { s.selectors.first("chat_input", it) } ?: run {
            d.back()
            return false
        }
        d.tapNode(field)
        d.pause(500, 1000)
        s.gestures.type(field, text)
        d.pause(900, 2200)
        if (!d.nameNearTop(name)) throw Stopped("chat changed before sending; nothing sent")
        if (!d.tap("chat_send")) throw Stopped("can't find the send button")

        val confirmed = awaitText(text.take(24))
        if (confirmed) {
            s.db.recordOpener(name, s.db.profileByName(name)?.first, text)
            Notifier.post(s, "New match: $name", "Opener sent: “$text”. It's yours from here.")
        } else {
            Notifier.post(s, "autoDate", "Tried to message $name but couldn't confirm it sent. Check the chat.")
        }
        d.back()
        return confirmed
    }

    /** Profile we judged earlier (we liked them), or capture it from the chat's Profile tab. */
    private fun profileFor(name: String): ProfileSnapshot? {
        s.db.profileByName(name)?.let { (key, text) -> return ProfileSnapshot(key, name, text, emptyList(), emptyList()) }
        if (!d.tap("chat_profile_tab")) return null
        d.pause(1200, 2200)
        val snap = s.reader.captureProfile(s.gestures, control, s.shots, maxSteps = 6)
        d.tap("chat_chat_tab")
        d.pause(1000, 1800)
        return snap
    }

    /** Messages visible in the chat body (between header and input). */
    private fun chatLines(name: String): List<String> {
        val root = s.reader.root() ?: return emptyList()
        val h = s.reader.screen.height()
        return root.walk()
            .filter { it.isVisibleToUser && it.bounds.top > h * 0.18 && it.bounds.bottom < h * 0.88 && !it.isEditable }
            .mapNotNull { it.label }
            .filter { it != name && it.length > 1 }
            .toList()
    }

    private fun awaitText(prefix: String): Boolean {
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end) {
            control.sleep(400)
            val root = s.reader.root() ?: continue
            if (root.walk().any { !it.isEditable && it.text?.toString()?.contains(prefix) == true }) return true
        }
        return false
    }

    private fun rowOf(label: AccessibilityNodeInfo): AccessibilityNodeInfo? = label.clickableAncestor()

    /** The match's name: the first short text in the row that isn't the "Your turn" label. */
    private fun rowName(row: AccessibilityNodeInfo, badge: AccessibilityNodeInfo): String? =
        row.walk().mapNotNull { it.text?.toString() }
            .firstOrNull { it.isNotBlank() && it != badge.text?.toString() && it.length <= 30 }

    companion object {
        private const val MAX_PER_RUN = 5
    }
}
