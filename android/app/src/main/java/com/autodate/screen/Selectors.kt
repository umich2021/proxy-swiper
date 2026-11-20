package com.autodate.screen

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One way of recognizing a node. Every field that is set must match; regexes are searched, not anchored,
 * unless they say ^...$. A node matches an element if it matches ANY of that element's rules.
 */
class Rule(
    private val id: Regex? = null,
    private val cls: String? = null,
    private val text: Regex? = null,
    private val desc: Regex? = null,
    private val hint: Regex? = null,
    private val action: Regex? = null,
    private val flags: List<String> = emptyList(),
) {
    fun matches(n: AccessibilityNodeInfo): Boolean {
        if (id != null && !id.containsMatchIn(n.viewIdResourceName.orEmpty())) return false
        if (cls != null && !n.className?.toString().orEmpty().contains(cls)) return false
        if (text != null && !text.containsMatchIn(n.text?.toString().orEmpty())) return false
        if (desc != null && !desc.containsMatchIn(n.contentDescription?.toString().orEmpty())) return false
        if (hint != null && !hint.containsMatchIn(n.hintText?.toString().orEmpty())) return false
        if (action != null && n.actionList.none { a -> a.label?.let { action.containsMatchIn(it) } == true }) return false
        for (f in flags) {
            val ok = when (f) {
                "clickable" -> n.isClickable
                "scrollable" -> n.isScrollable
                "editable" -> n.isEditable
                "selected" -> n.isSelected
                else -> true
            }
            if (!ok) return false
        }
        return true
    }

    fun toJson(): JSONObject = JSONObject().apply {
        id?.let { put("id", it.pattern) }
        cls?.let { put("cls", it) }
        text?.let { put("text", it.pattern) }
        desc?.let { put("desc", it.pattern) }
        hint?.let { put("hint", it.pattern) }
        action?.let { put("action", it.pattern) }
        if (flags.isNotEmpty()) put("flags", JSONArray(flags))
    }

    companion object {
        fun fromJson(o: JSONObject) = Rule(
            id = o.optString("id").takeIf { it.isNotEmpty() }?.toRegex(),
            cls = o.optString("cls").takeIf { it.isNotEmpty() },
            text = o.optString("text").takeIf { it.isNotEmpty() }?.toRegex(),
            desc = o.optString("desc").takeIf { it.isNotEmpty() }?.toRegex(),
            hint = o.optString("hint").takeIf { it.isNotEmpty() }?.toRegex(),
            action = o.optString("action").takeIf { it.isNotEmpty() }?.toRegex(),
            flags = o.optJSONArray("flags")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        )
    }
}

/**
 * Element definitions: assets/selectors.json, with an optional files/selectors.json on the phone
 * taking priority (push one with `adb shell run-as com.autodate.app` to test rules without a rebuild). Positions are screen fractions, used to tap when no rule matches.
 */
class Selectors(private val context: Context) {

    private var rules: Map<String, List<Rule>> = emptyMap()
    private var positions: Map<String, Pair<Float, Float>> = emptyMap()
    private val overrideFile get() = File(context.filesDir, "selectors.json")

    init {
        reload()
    }

    fun reload() {
        val shipped = JSONObject(context.assets.open("selectors.json").bufferedReader().use { it.readText() })
        val local = overrideFile.takeIf { it.exists() }?.let { JSONObject(it.readText()) } ?: JSONObject()
        val merged = mutableMapOf<String, List<Rule>>()
        for (src in listOf(shipped, local)) {
            val els = src.optJSONObject("elements") ?: continue
            els.keys().forEach { k ->
                val arr = els.getJSONArray(k)
                val parsed = (0 until arr.length()).map { Rule.fromJson(arr.getJSONObject(it)) }
                merged[k] = parsed + merged[k].orEmpty() // local (second) goes first
            }
        }
        rules = merged
        val pos = mutableMapOf<String, Pair<Float, Float>>()
        for (src in listOf(shipped, local)) {
            val p = src.optJSONObject("positions") ?: continue
            p.keys().forEach { k -> p.getJSONArray(k).let { pos[k] = it.getDouble(0).toFloat() to it.getDouble(1).toFloat() } }
        }
        positions = pos
    }

    val elementNames: Set<String> get() = rules.keys

    fun matches(element: String, n: AccessibilityNodeInfo): Boolean = rules[element].orEmpty().any { it.matches(n) }

    fun find(element: String, root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val rs = rules[element].orEmpty()
        if (rs.isEmpty()) return emptyList()
        return root.walk().filter { n -> n.isVisibleToUser && rs.any { it.matches(n) } }.toList()
    }

    fun first(element: String, root: AccessibilityNodeInfo): AccessibilityNodeInfo? = find(element, root).firstOrNull()

    fun position(element: String): Pair<Float, Float>? = positions[element]

    /** Which element (if any) a clicked node is, checking the node and its first few ancestors. */
    fun elementOf(node: AccessibilityNodeInfo, candidates: Collection<String> = elementNames): String? {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 4) {
            val hit = candidates.firstOrNull { matches(it, n!!) }
            if (hit != null) return hit
            n = n.parent
            hops++
        }
        // Or one of its children carries the label.
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            candidates.firstOrNull { matches(it, c) }?.let { return it }
        }
        return null
    }
}
