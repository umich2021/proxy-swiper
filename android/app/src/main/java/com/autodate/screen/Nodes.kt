package com.autodate.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_DEPTH = 80

/** Depth-first walk of an accessibility tree. */
fun AccessibilityNodeInfo.walk(): Sequence<AccessibilityNodeInfo> = sequence {
    suspend fun SequenceScope<AccessibilityNodeInfo>.visit(n: AccessibilityNodeInfo, depth: Int) {
        yield(n)
        if (depth >= MAX_DEPTH) return
        for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
    }
    visit(this@walk, 0)
}

val AccessibilityNodeInfo.bounds: Rect get() = Rect().also { getBoundsInScreen(it) }

/** The node's text, or its content description, whichever is set. */
val AccessibilityNodeInfo.label: String?
    get() = text?.toString()?.takeIf { it.isNotBlank() } ?: contentDescription?.toString()?.takeIf { it.isNotBlank() }

/** Nearest clickable node at or above this one (Hinge often puts the label on a child of the button). */
fun AccessibilityNodeInfo.clickableAncestor(): AccessibilityNodeInfo? {
    var n: AccessibilityNodeInfo? = this
    var hops = 0
    while (n != null && hops < 6) {
        if (n.isClickable) return n
        n = n.parent
        hops++
    }
    return null
}

/** Serializes the tree to JSON so we can see what's readable and tappable. */
object NodeDumper {

    fun dump(node: AccessibilityNodeInfo, depth: Int = 0): JSONObject {
        val o = summary(node)
        if (depth < MAX_DEPTH && node.childCount > 0) {
            val children = JSONArray()
            for (i in 0 until node.childCount) node.getChild(i)?.let { children.put(dump(it, depth + 1)) }
            o.put("children", children)
        }
        return o
    }

    /** One node without children: identity, text, bounds, and what it can do. */
    fun summary(node: AccessibilityNodeInfo): JSONObject {
        val r = node.bounds
        val o = JSONObject()
            .put("cls", node.className?.toString())
            .put("id", node.viewIdResourceName)
            .put("text", node.text?.toString())
            .put("desc", node.contentDescription?.toString())
            .put("hint", node.hintText?.toString())
            .put("bounds", "${r.left},${r.top},${r.right},${r.bottom}")

        val flags = JSONArray()
        if (node.isClickable) flags.put("clickable")
        if (node.isLongClickable) flags.put("longClickable")
        if (node.isScrollable) flags.put("scrollable")
        if (node.isEditable) flags.put("editable")
        if (node.isCheckable) flags.put("checkable")
        if (node.isSelected) flags.put("selected")
        if (!node.isVisibleToUser) flags.put("offscreen")
        if (flags.length() > 0) o.put("flags", flags)

        // Custom actions (e.g. a Compose "Like" action) show up with a label.
        val actions = JSONArray()
        node.actionList.forEach { a -> a.label?.let { actions.put(it.toString()) } }
        if (actions.length() > 0) o.put("actions", actions)
        return o
    }

    /** All visible text and content descriptions, in tree order. */
    fun texts(root: AccessibilityNodeInfo): List<String> = root.walk()
        .filter { it.isVisibleToUser }
        .flatMap { n ->
            listOfNotNull(
                n.text?.toString()?.takeIf { it.isNotBlank() },
                n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { "[desc] $it" },
            )
        }
        .toList()
}
