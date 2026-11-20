package com.autodate.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The only code that touches the screen. Taps land on a right-thumb-biased Gaussian inside the
 * target, press duration is Gaussian, swipes vary in length and speed, and text is typed one key at a time.
 * [guard] runs before every gesture and throws [Stopped] if Hinge isn't in front.
 */
class GestureEngine(
    private val service: AccessibilityService,
    private val control: Control,
    private val guard: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    val screen: Rect get() = service.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds

    fun tap(bounds: Rect) {
        guard()
        val meanX = bounds.exactCenterX() + bounds.width() * Jitter.thumbX * 0.5f
        val meanY = bounds.exactCenterY() + bounds.height() * Jitter.thumbY * 0.5f
        val x = Jitter.gaussian(meanX, bounds.width() * 0.12f, bounds.left + 2f, bounds.right - 2f)
        val y = Jitter.gaussian(meanY, bounds.height() * 0.12f, bounds.top + 2f, bounds.bottom - 2f)
        val roll = Jitter.gaussian(5.5f, 1.4f, 2.5f, 9f)
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + roll, y + roll)
        }
        dispatch(path, Jitter.gaussian(82.0, 22.0, 42L, 165L))
    }

    /** Tap at a position given as screen fractions. */
    fun tapAt(fx: Float, fy: Float) {
        val s = screen
        val half = (s.width() * 0.02f).toInt()
        val cx = (fx * s.width()).toInt()
        val cy = (fy * s.height()).toInt()
        tap(Rect(cx - half, cy - half, cx + half, cy + half))
    }

    /** Scrolls a profile. forward = finger moves up (content moves up). Returns signed finger travel in px. */
    fun scroll(forward: Boolean): Int = scroll(forward, Jitter.range(0.42f, 0.6f))

    /** A shorter swipe down the page, about a third of a screen, for looking through a profile closely. */
    fun scrollShort(): Int = scroll(true, Jitter.range(0.28f, 0.36f))

    private fun scroll(forward: Boolean, fraction: Float): Int {
        guard()
        val s = screen
        val h = s.height().toFloat()
        val w = s.width().toFloat()
        val travel = h * fraction
        val x0 = w * Jitter.range(0.35f, 0.65f)
        // Stay above the bottom tabs. A swipe that starts on Profile opens your own profile.
        val y0 = if (forward) h * Jitter.range(0.58f, 0.66f) else h * Jitter.range(0.28f, 0.36f)
        val y1 = (if (forward) y0 - travel else y0 + travel).coerceIn(h * 0.18f, h * 0.68f)
        val drift = w * Jitter.range(-0.05f, 0.05f)
        val path = Path().apply {
            moveTo(x0, y0)
            quadTo(x0 + drift * 0.3f + Jitter.range(-20f, 20f), (y0 + y1) / 2, x0 + drift, y1)
        }
        dispatch(path, Jitter.wait(300, 650))
        // What the finger actually moved, after keeping clear of the tab bar (positive = scrolled down).
        return (y0 - y1).toInt()
    }

    /**
     * Swipes a sideways-scrolling row (Hinge's basics) so more of it shows: the finger moves right to
     * left, under half the row's width, so the row moves less than a screenful and nothing is skipped.
     */
    fun swipeRow(row: Rect) {
        guard()
        val w = row.width().toFloat()
        val travel = w * Jitter.range(0.38f, 0.48f)
        val x0 = row.left + w * Jitter.range(0.74f, 0.86f)
        val y0 = Jitter.gaussian(row.exactCenterY(), row.height() * 0.12f, row.top + 4f, row.bottom - 4f)
        val path = Path().apply {
            moveTo(x0, y0)
            quadTo(x0 - travel / 2, y0 + Jitter.range(-8f, 8f), x0 - travel, y0 + Jitter.range(-14f, 14f))
        }
        dispatch(path, Jitter.wait(380, 600))
    }

    /** Called with true before each gesture and false after. The bubble uses it to let our own taps through. */
    var onGesture: (Boolean) -> Unit = {}

    /** Enters text into an editable node one key at a time. */
    fun type(field: AccessibilityNodeInfo, text: String) {
        if (!tryType(field, text)) throw Stopped("couldn't type into the text field")
    }

    /** Like [type], but returns false if the node won't take text. */
    fun tryType(field: AccessibilityNodeInfo, text: String): Boolean {
        val parts = Jitter.graphemes(text)
        var end = 0
        for ((i, g) in parts.withIndex()) {
            guard()
            end += g.length
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text.substring(0, end))
            }
            if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
            if (i < parts.lastIndex) control.sleep(Jitter.keyPause(g))
        }
        return true
    }

    private fun dispatch(path: Path, durationMs: Long) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val latch = CountDownLatch(1)
        var completed = false
        onGesture(true)
        val posted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) {
                completed = true
                latch.countDown()
            }

            override fun onCancelled(g: GestureDescription) = latch.countDown()
        }, main)
        if (!posted) {
            onGesture(false)
            throw Stopped("Android rejected the gesture")
        }
        latch.await(durationMs + 2000, TimeUnit.MILLISECONDS)
        onGesture(false)
        // A real touch on the screen cancels our gesture: treat that as "you took the phone back".
        if (!completed) throw Stopped("gesture interrupted (screen touched?)")
    }
}
