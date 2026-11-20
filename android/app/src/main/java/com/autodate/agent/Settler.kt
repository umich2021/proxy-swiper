package com.autodate.agent

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.min

/**
 * Runs [action] once the screen stops changing for [quietMs], but never later than [maxWaitMs]
 * after the first change. Hinge (Jetpack Compose) sends a stream of content-changed events.
 */
class Settler(private val quietMs: Long, private val maxWaitMs: Long, private val action: () -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var firstAt = 0L
    private val run = Runnable {
        firstAt = 0L
        action()
    }

    fun poke() {
        val now = SystemClock.uptimeMillis()
        if (firstAt == 0L) firstAt = now
        main.removeCallbacks(run)
        main.postDelayed(run, min(quietMs, maxWaitMs - (now - firstAt)).coerceAtLeast(0))
    }

    fun cancel() {
        main.removeCallbacks(run)
        firstAt = 0L
    }
}
