package com.autodate.screen

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Base64
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import com.autodate.agent.Control
import com.autodate.agent.GestureEngine
import com.autodate.agent.Jitter
import com.autodate.agent.Stopped
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.abs

const val HINGE = "co.hinge.app"

enum class Screen { DISCOVER, COMMENT_SHEET, OUT_OF_LIKES, CHAT, POPUP, UNKNOWN }

/** Something on a profile that can be liked. [absY] is its position within the whole scrolled profile. */
data class Likeable(val index: Int, val kind: String, val label: String, val absY: Int) {
    fun toJson(): JSONObject = JSONObject().put("index", index).put("kind", kind).put("label", label)
}

class ProfileSnapshot(
    val key: String,
    val name: String,
    val text: String,
    val images: List<ByteArray>,
    val likeables: List<Likeable>,
) {
    fun likeablesJson() = JSONArray(likeables.map { it.toJson() })

    fun toBrainJson(): JSONObject = JSONObject()
        .put("text", text)
        .put("images", JSONArray(images.map { Base64.encodeToString(it, Base64.NO_WRAP) }))
        .put("likeables", likeablesJson())

    companion object {
        fun keyFor(texts: List<String>): String = MessageDigest.getInstance("SHA-1")
            .digest(texts.take(3).joinToString("|").toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
    }
}

/** Reads Hinge: which screen is showing, what a profile says, where its like buttons are. */
class ScreenReader(private val service: AccessibilityService, private val sel: Selectors) {

    /** Scroll offset of the current profile relative to its top, tracked while we scroll. */
    var offset = 0
        private set

    val screen: Rect get() = service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds

    fun root(): AccessibilityNodeInfo? = service.rootInActiveWindow?.takeIf { it.packageName?.toString() == HINGE }

    fun has(element: String, root: AccessibilityNodeInfo) = sel.first(element, root) != null

    fun classify(root: AccessibilityNodeInfo? = root()): Screen {
        root ?: return Screen.UNKNOWN
        return when {
            has("out_of_likes", root) -> Screen.OUT_OF_LIKES
            has("send_like", root) || has("comment_field", root) -> Screen.COMMENT_SHEET
            has("dismiss", root) -> Screen.POPUP
            has("skip_button", root) -> Screen.DISCOVER // Standouts has hearts but no skip
            has("chat_input", root) -> Screen.CHAT
            else -> Screen.UNKNOWN
        }
    }

    /** Polls until one of [screens] shows up, or returns the last screen seen after [timeoutMs]. */
    fun waitFor(control: Control, timeoutMs: Long, vararg screens: Screen): Screen {
        val end = System.currentTimeMillis() + timeoutMs
        var s = classify()
        while (s !in screens && System.currentTimeMillis() < end) {
            control.sleep(250)
            s = classify()
        }
        return s
    }

    /**
     * Visible profile content in tree order: inside the profile scroller, no buttons or tab bar.
     * Basics (an icon described as "Smoking" next to its value) come out as one "Smoking: No" line,
     * so a value can't end up next to the wrong label, and two "Yes" values both survive.
     */
    fun profileTexts(root: AccessibilityNodeInfo): List<String> {
        val area = sel.find("profile_scroller", root).maxByOrNull { it.bounds.height() }?.bounds
        val out = mutableListOf<String>()
        val paired = HashSet<AccessibilityNodeInfo>()
        for (n in root.walk()) {
            if (n in paired || !n.isVisibleToUser) continue
            if (area != null && n.bounds.let { it.top < area.top || it.top >= area.bottom }) continue
            val pair = basicPair(n)
            if (pair != null) {
                out += pair
                for (i in 0 until n.childCount) n.getChild(i)?.let { paired += it }
                continue
            }
            if (n.className?.toString()?.endsWith("Button") == true) continue
            n.label?.let { out += it }
        }
        return out
    }

    /**
     * "Label: Value" for one of Hinge's basics, or null if [n] isn't one. A basic is an unlabeled node
     * whose first child is an icon with only a description ("Age", "Smoking") and whose other children
     * are plain text values. Both the sideways chips and the rows under them look like this. Values
     * are read even when cut off at the screen edge.
     */
    private fun basicPair(n: AccessibilityNodeInfo): String? {
        if (n.childCount !in 2..4 || n.label != null) return null
        val kids = (0 until n.childCount).mapNotNull { n.getChild(it) }
        if (kids.size != n.childCount) return null
        val icon = kids.first()
        if (!icon.text.isNullOrBlank() || icon.className?.toString()?.endsWith("TextView") == true) return null
        val label = icon.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 } ?: return null
        val values = kids.drop(1).mapNotNull { k -> k.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && k.childCount == 0 } }
        if (values.size != kids.size - 1) return null
        return "$label: ${values.joinToString(", ")}"
    }

    /** The sideways-scrolling basics row (Age, Height, Drinking, Smoking, …) when it's well inside the screen, or null. */
    private fun basicsRow(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val h = screen.height()
        return sel.find("basics_row", root).firstOrNull { n ->
            val b = n.bounds
            n.isVisibleToUser && b.height() < h * 0.2 && b.top > h * 0.15 && b.bottom < h * 0.85 &&
                (0 until n.childCount).any { i -> n.getChild(i)?.let { basicPair(it) } != null }
        }
    }

    /**
     * Hinge only puts the chips that are on screen into the accessibility tree, so Drinking, Smoking
     * and the rest only show up after the row is swiped. Swipes it until nothing new appears, adding
     * each "Label: Value" to [into]. Returns false if the row isn't on screen yet.
     */
    private fun readBasicsRow(g: GestureEngine, control: Control, into: MutableSet<String>): Boolean {
        var row = root()?.let { basicsRow(it) } ?: return false
        for (swipe in 0..8) {
            val added = (0 until row.childCount).mapNotNull { row.getChild(it)?.let(::basicPair) }.count { into.add(it) }
            if (swipe > 0 && added == 0) break
            if (swipe == 8) break
            g.swipeRow(row.bounds)
            control.sleep(Jitter.wait(450, 750))
            row = root()?.let { basicsRow(it) } ?: break
        }
        return true
    }

    /** The profile's name, from the skip button ("Skip Peyton"). */
    fun currentName(root: AccessibilityNodeInfo): String? =
        sel.first("skip_button", root)?.contentDescription?.toString()
            ?.replaceFirst(Regex("(?i)^skip\\s*"), "")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Scrolls through the profile on screen top to bottom, collecting text, screenshots and like
     * buttons, pausing like someone reading. Leaves the view at the bottom; [offset] tracks where.
     * [fromTop] scrolls up first, for a profile that may already be scrolled (the first one of a
     * session, where you may have left it mid-way). Otherwise a new profile opens at its top.
     */
    fun captureProfile(g: GestureEngine, control: Control, shots: Screenshots, maxSteps: Int = 9, fromTop: Boolean = false): ProfileSnapshot {
        if (fromTop) scrollToTop(g, control)
        offset = 0
        val texts = LinkedHashSet<String>()
        val likeables = mutableListOf<Likeable>()
        val images = mutableListOf<ByteArray>()
        var prev = keyTops(root() ?: throw Stopped("Hinge left the foreground"))
        var basicsRead = false
        for (step in 0..maxSteps) {
            val root = root() ?: throw Stopped("Hinge left the foreground")
            val before = texts.size
            texts += profileTexts(root)
            if (!basicsRead && readBasicsRow(g, control, texts)) {
                basicsRead = true
                prev = keyTops(root() ?: throw Stopped("Hinge left the foreground"))
            }
            collectLikeables(root, likeables, offset)
            if (images.size < 6) shots.jpeg(control)?.let { images += it }
            val newChars = texts.drop(before).sumOf { it.length }
            control.sleep(Jitter.logNormal(700.0 + newChars * 25.0).coerceAtMost(6000))
            if (step == maxSteps) break
            val travel = g.scroll(forward = true)
            control.sleep(Jitter.wait(450, 800))
            val now = keyTops(root() ?: throw Stopped("Hinge left the foreground"))
            val shift = estimateShift(prev, now, travel) ?: break
            offset += shift
            prev = now
            if (step >= 1 && Jitter.chance(0.16)) {
                lookBack(g, control)
                prev = keyTops(root() ?: throw Stopped("Hinge left the foreground"))
            }
        }
        val list = texts.toList()
        val name = root()?.let { currentName(it) } ?: list.firstOrNull().orEmpty()
        return ProfileSnapshot(ProfileSnapshot.keyFor(listOf(name) + list), name, "Name: $name\n" + list.joinToString("\n"), images, likeables)
    }

    /** Scroll up to look at something again, pause, then continue down. */
    private fun lookBack(g: GestureEngine, control: Control) {
        val before = keyTops(root() ?: throw Stopped("Hinge left the foreground"))
        val back = g.scroll(forward = false)
        control.sleep(Jitter.wait(550, 1600))
        val mid = keyTops(root() ?: throw Stopped("Hinge left the foreground"))
        offset += estimateShift(before, mid, back) ?: 0
        control.sleep(Jitter.logNormal(900.0).coerceAtMost(2500))
        val again = g.scroll(forward = true)
        control.sleep(Jitter.wait(400, 750))
        val after = keyTops(root() ?: throw Stopped("Hinge left the foreground"))
        offset += estimateShift(mid, after, again) ?: 0
    }

    /**
     * Scrolls back to [target] and returns its like button, or null if it can't be found.
     * Starts from the top of the profile: [offset] is only right if nobody else scrolled since the
     * capture, and you may have scrolled the profile yourself while the approval card was up.
     * Position estimates drift on long profiles, so a prompt is also matched by its text.
     */
    fun findLikeButton(target: Likeable, known: List<Likeable>, g: GestureEngine, control: Control): AccessibilityNodeInfo? {
        val h = screen.height()
        val promptText = target.label.substringBefore(" — ").takeIf { target.kind in TEXT_KINDS && it.isNotBlank() }
        val option = target.label.substringAfter(" — ", "").takeIf { target.kind == "poll" && it.isNotBlank() }
        scrollToTop(g, control)
        repeat(14) {
            val root = root() ?: throw Stopped("Hinge left the foreground")
            val visible = sel.find("like_button", root).filter { it.bounds.centerY() in (h * 0.08).toInt()..(h * 0.78).toInt() }
            recalibrate(root, visible, known)
            if (option != null) {
                visible.firstOrNull { it.label == option }?.let { return it }
            } else if (promptText != null) {
                visible.firstOrNull { btn -> !btn.isPollOption() && labelsNear(root, btn).any { sameText(it, promptText) } }?.let { return it }
            }
            val y = target.absY - offset
            // By position, for hearts only: a poll option is only ever matched by its own text.
            if (option == null && y in (h * 0.12).toInt()..(h * 0.9).toInt()) {
                val btn = visible.filter { !it.isPollOption() }.minByOrNull { abs(it.bounds.centerY() + offset - target.absY) }
                if (btn != null && abs(btn.bounds.centerY() + offset - target.absY) < 220) return btn
            }
            val before = keyTops(root)
            val travel = g.scroll(forward = y > h * 0.9 || promptText != null)
            control.sleep(Jitter.wait(400, 700))
            val after = keyTops(root() ?: throw Stopped("Hinge left the foreground"))
            offset += estimateShift(before, after, travel) ?: return lookAgain(target, g, control)
        }
        return lookAgain(target, g, control)
    }

    /**
     * Second try for [findLikeButton]: reads the profile again from the top, in short scrolls, the way
     * it was read the first time, and finds [target] among what it sees now: a prompt or poll option
     * by its text, a photo or video by its place in the order ("photo 2" is the second photo heart).
     * Doesn't rely on positions saved earlier, which go wrong when the profile was scrolled since.
     */
    private fun lookAgain(target: Likeable, g: GestureEngine, control: Control): AccessibilityNodeInfo? {
        val h = screen.height()
        scrollToTop(g, control)
        val fresh = mutableListOf<Likeable>()
        repeat(20) {
            val root = root() ?: throw Stopped("Hinge left the foreground")
            collectLikeables(root, fresh, offset)
            val match = fresh.firstOrNull { it.kind == target.kind && sameItem(it, target) }
            if (match != null) {
                val btn = sel.find("like_button", root)
                    .filter { it.bounds.centerY() in (h * 0.08).toInt()..(h * 0.78).toInt() }
                    .filter { (target.kind == "poll") == it.isPollOption() }
                    .minByOrNull { abs(it.bounds.centerY() + offset - match.absY) }
                if (btn != null && abs(btn.bounds.centerY() + offset - match.absY) < 150) return btn
            }
            val before = keyTops(root)
            val travel = g.scrollShort()
            control.sleep(Jitter.wait(450, 800))
            val after = keyTops(root() ?: throw Stopped("Hinge left the foreground"))
            offset += estimateShift(before, after, travel) ?: return null // the bottom
        }
        return null
    }

    private fun sameItem(a: Likeable, b: Likeable): Boolean = when (a.kind) {
        "photo", "video", "poll" -> a.label == b.label // a poll's options share its question
        else -> sameText(a.label, b.label)
    }

    /** Same prompt text, ignoring spacing, quote style and where either copy was cut off. */
    private fun sameText(a: String, b: String): Boolean {
        val x = norm(a.substringBefore(" — ")).take(60)
        val y = norm(b.substringBefore(" — ")).take(60)
        return x.isNotEmpty() && y.isNotEmpty() && (x.startsWith(y) || y.startsWith(x))
    }

    private fun norm(t: String) = t.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
        .replace(Regex("\\s+"), " ").trim().lowercase()

    /**
     * Re-anchors [offset] on prompts we can see and recognize by their text. Photos are only ever found
     * by position, and the position estimate drifts with every scroll (yours while the card was hidden
     * included), so each recognized prompt resets it to what was measured while reading.
     */
    private fun recalibrate(root: AccessibilityNodeInfo, visible: List<AccessibilityNodeInfo>, known: List<Likeable>) {
        val prompts = known.filter { it.kind in TEXT_KINDS && it.kind != "poll" }
        if (prompts.isEmpty()) return
        val nodes = textNodes(root)
        val estimates = visible.filter { !it.isPollOption() }.mapNotNull { btn ->
            val near = labelsNear(btn, nodes)
            val match = prompts.firstOrNull { p -> p.label.substringBefore(" — ").let { it.isNotBlank() && it in near } }
            match?.let { it.absY - btn.bounds.centerY() }
        }.sorted()
        if (estimates.isNotEmpty()) offset = estimates[estimates.size / 2]
    }

    /**
     * What the open like sheet is attached to: the photo or prompt shown just above its comment box.
     * Null when it can't be read.
     */
    fun sheetItem(root: AccessibilityNodeInfo? = root()): String? {
        root ?: return null
        val box = sel.first("comment_field", root) ?: return null
        val top = box.bounds.top
        return root.walk().filter { it.isVisibleToUser && it.label != null && it.bounds.bottom <= top + 10 && it.bounds.height() > 150 }
            .filter { n -> n.label!!.let { it.startsWith("Prompt:") || it.startsWith("Voice Prompt:") || PHOTO_LABEL.containsMatchIn(it) || "video" in it.lowercase() } }
            .maxByOrNull { it.bounds.bottom }?.label
    }

    /** False when the open like sheet is clearly on a different item than [target]; null when it can't tell. */
    fun sheetMatches(target: Likeable): Boolean? {
        val item = sheetItem() ?: return null
        val isPrompt = item.startsWith("Prompt:") || item.startsWith("Voice Prompt:")
        // A video prompt's heart reads as a prompt (its label is "Video prompt: <question>"), so its sheet counts as one too.
        val isVideoPrompt = item.startsWith("Video prompt:", ignoreCase = true)
        return when (target.kind) {
            "photo", "video" -> !isPrompt
            "prompt", "voice" -> {
                val want = target.label.substringBefore(" — ").take(50)
                (isPrompt || isVideoPrompt) && want.isNotBlank() && item.startsWith(want.take(item.length))
            }
            else -> null // a poll option's sheet isn't worth guessing at
        }
    }

    /** Last resort when the chosen item can't be found: the first like button from the top (usually the main photo). */
    fun firstLikeButton(g: GestureEngine, control: Control): AccessibilityNodeInfo? {
        scrollToTop(g, control)
        val root = root() ?: throw Stopped("Hinge left the foreground")
        return sel.find("like_button", root).filter { it.isVisibleToUser && !it.isPollOption() }.minByOrNull { it.bounds.top }
    }

    /** Scrolls up until nothing moves, then sets [offset] to 0. */
    private fun scrollToTop(g: GestureEngine, control: Control, maxSteps: Int = 14) {
        for (step in 0 until maxSteps) {
            val before = keyTops(root() ?: throw Stopped("Hinge left the foreground"))
            val travel = g.scroll(forward = false)
            control.sleep(Jitter.wait(350, 600))
            val after = keyTops(root() ?: throw Stopped("Hinge left the foreground"))
            if (estimateShift(before, after, travel) == null) break
        }
        offset = 0
    }

    /**
     * Adds like targets not already known: hearts on photos, videos and prompts, and the options of
     * a poll prompt (on a poll you like an option, there's no heart).
     *
     * Hinge labels hearts "Like photo", "Like prompt", "Like photo prompt" (a photo with a caption)
     * or just "Like", so the kind comes from the description first and the text next to the heart
     * second. Prompts and poll options are matched by their text; photos by position, with a wide
     * margin because position estimates drift as the profile scrolls.
     */
    fun collectLikeables(root: AccessibilityNodeInfo, into: MutableList<Likeable>, offset: Int) {
        val textNodes = textNodes(root)
        for (btn in sel.find("like_button", root)) {
            val absY = btn.bounds.centerY() + offset
            val desc = btn.label.orEmpty()
            val nearby = labelsNear(btn, textNodes)
            val kind = kindOf(desc, nearby)
            val label = when (kind) {
                "photo" -> "photo ${into.count { it.kind == "photo" } + 1}"
                "video" -> "video ${into.count { it.kind == "video" } + 1}"
                "poll" -> {
                    val question = nearby.lastOrNull { it.startsWith("Prompt:") }.orEmpty()
                    "$question — $desc".take(200)
                }
                else -> nearby.joinToString(" — ").take(160)
            }
            val known = when (kind) {
                "photo", "video" -> into.any { it.kind == kind && abs(it.absY - absY) < PHOTO_MARGIN }
                else -> into.any { it.kind == kind && it.label == label }
            }
            if (!known) into += Likeable(into.size, kind, label, absY)
        }
    }

    private fun kindOf(desc: String, nearby: List<String>): String {
        val d = desc.lowercase()
        val photoish = nearby.any { PHOTO_LABEL.containsMatchIn(it) }
        return when {
            d.startsWith("option:") -> "poll"
            "photo" in d || "picture" in d -> "photo" // includes "Like photo prompt"
            "video" in d -> "video"
            "voice" in d -> "voice"
            "poll" in d -> "poll"
            "prompt" in d -> "prompt"
            nearby.any { it.startsWith("Voice Prompt:") } -> "voice"
            nearby.any { it.startsWith("Prompt:") || DATE_IDEAS.containsMatchIn(it) } -> "prompt"
            nearby.any { it.contains("'s video") || it.contains("’s video") } -> "video"
            photoish || nearby.isEmpty() -> "photo"
            else -> "prompt"
        }
    }

    private fun textNodes(root: AccessibilityNodeInfo) = root.walk().filter {
        it.isVisibleToUser && it.label != null && !it.isClickable && !sel.matches("like_button", it)
    }.toList()

    private fun AccessibilityNodeInfo.isPollOption() = label?.startsWith("Option:") == true

    private companion object {
        /** Photos are about a screen tall, so two hearts this close are the same photo. */
        const val PHOTO_MARGIN = 400
        val TEXT_KINDS = setOf("prompt", "voice", "poll")
        val PHOTO_LABEL = Regex("(?i)(['’]s photo|selfie verified|photo prompt:)")
        val DATE_IDEAS = Regex("(?i)^let['’]s get together")
        val AMBIGUOUS = Regex("(?i)(['’]s (photo|video)$|^like( (photo|prompt|video|voice|poll|answer)\\b.*)?$)")
        /** Labels that change on their own (a playing video's clock), so they can't show whether the page moved. */
        val VOLATILE = Regex("(?i)^elapsed time")
    }

    /** Text just above a like button (its prompt, if it has one), top to bottom. */
    private fun labelsNear(btn: AccessibilityNodeInfo, textNodes: List<AccessibilityNodeInfo>): List<String> {
        val b = btn.bounds
        return textNodes
            .filter { val t = it.bounds; t.bottom <= b.bottom + 80 && t.bottom >= b.top - 450 }
            .sortedBy { it.bounds.top }
            .mapNotNull { it.label }
            .let { all -> all.filter { it.startsWith("Prompt:") }.ifEmpty { all } }
    }

    private fun labelsNear(root: AccessibilityNodeInfo, btn: AccessibilityNodeInfo) = labelsNear(btn, textNodes(root))

    /**
     * Where labeled things sit on one screen, used to measure scrolling. [unique] holds labels seen once
     * (safe to compare across screens); [all] holds every label's tops, repeated ones like "Like photo" included.
     */
    private class Layout(val unique: Map<String, Int>, val all: Map<String, List<Int>>)

    private fun keyTops(root: AccessibilityNodeInfo): Layout {
        val h = screen.height()
        val seen = mutableMapOf<String, MutableList<Int>>()
        root.walk().filter { it.isVisibleToUser }.forEach { n ->
            val l = n.label ?: return@forEach
            if (VOLATILE.containsMatchIn(l)) return@forEach
            val top = n.bounds.top
            if (top > h * 0.05 && top < h * 0.95) seen.getOrPut(l) { mutableListOf() } += top
        }
        // "Like photo" / "Ana's photo" repeat down the profile, so on two screens they can be different items.
        val unique = seen.filterKeys { !AMBIGUOUS.containsMatchIn(it) }.filterValues { it.size == 1 }.mapValues { it.value[0] }
        return Layout(unique, seen)
    }

    /**
     * How far content moved between two screens (positive = scrolled down), or null if it didn't.
     * Fixed elements (header, Skip, the tab bar) never move and are ignored.
     */
    private fun estimateShift(prev: Layout, now: Layout, travel: Int): Int? {
        val moving = prev.unique.keys.intersect(now.unique.keys)
            .map { prev.unique.getValue(it) - now.unique.getValue(it) }
            .filter { abs(it) >= 15 }.sorted()
        if (moving.isNotEmpty()) return moving[moving.size / 2]
        // A screen of photos has only repeated labels ("Ana's photo", "Like photo"), so nothing unique moves.
        // Pair repeated labels up instead: the shift in the swipe's direction that lines up the most of them,
        // closest to how far the finger moved. Without this, two photos on screen read as "didn't move",
        // which ended the search for a like button early and made "top of the profile" land mid-profile.
        // Only when they actually changed: at the bottom, two photo hearts that stayed put would still
        // "pair" with each other at the distance between them.
        val repeatedMoved = (prev.all - prev.unique.keys) != (now.all - now.unique.keys)
        val candidates = if (!repeatedMoved) emptyList() else prev.all.flatMap { (label, tops) ->
            now.all[label].orEmpty().flatMap { n -> tops.map { it - n } }
        }.filter { abs(it) >= 15 && (it > 0) == (travel > 0) && abs(it) <= abs(travel) * 2 + 200 }
        if (candidates.isNotEmpty()) {
            return candidates.groupBy { (it + 10) / 20 }.values
                .maxWith(compareBy<List<Int>> { it.size }.thenBy { -abs(it.average() - travel) })
                .let { it.sorted()[it.size / 2] }
        }
        // Nothing pairs up: the whole screen changed (moved a lot), or nothing did.
        val newLabels = now.all.keys - prev.all.keys
        val goneLabels = prev.all.keys - now.all.keys
        return if (newLabels.isNotEmpty() || goneLabels.isNotEmpty()) travel else null
    }
}
