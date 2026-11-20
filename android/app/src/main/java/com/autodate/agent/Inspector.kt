package com.autodate.agent

import android.view.accessibility.AccessibilityEvent
import com.autodate.data.CaptureStore
import com.autodate.screen.NodeDumper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Inspect mode (Phase 0): records Hinge's node tree + a screenshot whenever the screen settles,
 * and every button you tap. Also used by the session to save a capture of an unknown screen.
 */
class Inspector(private val s: AgentService) {

    private val io = Executors.newSingleThreadExecutor()
    private val store by lazy { CaptureStore.newSession(s) }
    private var screenName: String? = null
    private val settler = Settler(quietMs = 1500, maxWaitMs = 3000) { capture("auto") }

    fun onEvent(e: AccessibilityEvent) {
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                screenName = e.className?.toString()
                scheduleAuto()
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> scheduleAuto()
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                store.appendClick(describeClick(e))
                capture("click")
            }
        }
    }

    fun captureManual() = capture("manual")

    /** Saves the current Hinge screen. Safe from any thread. */
    fun capture(reason: String) {
        settler.cancel()
        val root = s.reader.root() ?: run {
            if (reason == "manual") s.overlay.flash("Open Hinge first")
            return
        }
        val index = store.nextIndex()
        val doc = JSONObject()
            .put("index", index)
            .put("ts", System.currentTimeMillis())
            .put("reason", reason)
            .put("screen", screenName)
            .put("texts", JSONArray(NodeDumper.texts(root)))
            .put("tree", NodeDumper.dump(root))
        io.execute { store.writeDump(index, reason, doc) }
        s.shots.take { bmp, err ->
            io.execute {
                if (bmp != null) store.writeShot(index, bmp) else store.note(index, "screenshot skipped: $err")
            }
        }
        s.overlay.flash("✓ $index", 700)
    }

    private fun scheduleAuto() {
        if (s.config.autoCapture) settler.poke()
    }

    private fun describeClick(e: AccessibilityEvent): JSONObject {
        val o = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("screen", screenName)
            .put("cls", e.className?.toString())
            .put("text", e.text.joinToString(" | "))
            .put("desc", e.contentDescription?.toString())
        e.source?.let { src ->
            o.put("node", NodeDumper.summary(src))
            o.put("matched_element", s.selectors.elementOf(src))
            // Parents usually say which photo/prompt a heart button belongs to.
            val parents = JSONArray()
            var p = src.parent
            while (p != null && parents.length() < 4) {
                parents.put(NodeDumper.summary(p))
                p = p.parent
            }
            o.put("parents", parents)
        }
        return o
    }

    fun shutdown() {
        settler.cancel()
        io.shutdown()
    }
}
