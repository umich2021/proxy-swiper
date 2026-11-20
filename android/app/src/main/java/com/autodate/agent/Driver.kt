package com.autodate.agent

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.autodate.screen.Screen
import com.autodate.screen.ScreenReader
import com.autodate.screen.Selectors
import com.autodate.screen.bounds
import com.autodate.screen.clickableAncestor
import com.autodate.screen.label
import com.autodate.screen.walk

/** Small navigation verbs built on selectors + gestures, shared by the session and the inbox. */
class Driver(
    private val service: AccessibilityService,
    private val sel: Selectors,
    private val reader: ScreenReader,
    private val g: GestureEngine,
    private val control: Control,
) {
    /** Taps an element found by its rules, falling back to a position from selectors.json. */
    fun tap(element: String, root: AccessibilityNodeInfo? = reader.root()): Boolean {
        val node = root?.let { sel.first(element, it) }
        if (node != null) {
            tapNode(node)
            return true
        }
        sel.position(element)?.let { (fx, fy) ->
            g.tapAt(fx, fy)
            return true
        }
        return false
    }

    /**
     * Taps a node. Uses its clickable ancestor when that's about the same size (a label inside a
     * button); otherwise the node itself. Hinge's tab labels sit next to, not inside, their
     * clickable area, so tapping a far larger ancestor would hit the wrong spot.
     */
    fun tapNode(node: AccessibilityNodeInfo) {
        val own = node.bounds
        val ancestor = node.clickableAncestor()?.bounds
        val ownArea = own.width().toLong() * own.height()
        val useAncestor = ancestor != null && ownArea > 0 && ancestor.width().toLong() * ancestor.height() <= ownArea * 4
        g.tap(if (useAncestor) ancestor!! else own)
    }

    /**
     * Hinge's own back arrow, or else system Back. Never system Back on Discover or with Hinge out of
     * front: Discover is Hinge's top level, so Back there closes Hinge.
     */
    fun back() {
        val root = reader.root() ?: return
        val node = sel.first("back", root)
        when {
            node != null -> tapNode(node)
            reader.classify(root) == Screen.DISCOVER -> return
            else -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
        pause(900, 1600)
    }

    fun pause(minMs: Long, maxMs: Long) = control.sleep(Jitter.wait(minMs, maxMs))

    /** Waits up to [timeoutMs] for Hinge to be the active window. */
    fun awaitHinge(timeoutMs: Long = 10_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (reader.root() == null) {
            if (System.currentTimeMillis() > end) throw Stopped("Hinge didn't come to the front")
            control.sleep(300)
        }
    }

    /** True if [name] appears near the top of the screen (the chat header). Guards against wrong-chat sends. */
    fun nameNearTop(name: String): Boolean {
        val root = reader.root() ?: return false
        val limit = reader.screen.height() * 0.2
        return root.walk().any { n -> n.isVisibleToUser && n.bounds.top < limit && n.label?.contains(name, ignoreCase = true) == true }
    }

    /** First few profile texts, used to confirm the card actually changed after a skip/like. */
    fun topTexts(): List<String> = reader.root()?.let { reader.profileTexts(it).take(4) }.orEmpty()

    /** Waits until the profile on screen is a different one than [before]. */
    fun awaitNewProfile(before: List<String>, timeoutMs: Long = 4000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            control.sleep(300)
            val now = topTexts()
            if (now.isNotEmpty() && now != before) return true
        }
        return false
    }
}
