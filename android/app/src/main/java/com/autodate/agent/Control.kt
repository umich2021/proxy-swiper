package com.autodate.agent

import java.text.BreakIterator
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/** Thrown to unwind a running session. Every stop path ends here, never in a like. */
class Stopped(val reason: String) : Exception(reason)

/** Stop flag shared between the worker thread and the UI (bubble, volume-down, errors). */
class Control {
    @Volatile
    var stopReason: String? = null
        private set

    fun reset() {
        stopReason = null
    }

    fun stop(reason: String) {
        if (stopReason == null) stopReason = reason
    }

    fun check() {
        stopReason?.let { throw Stopped(it) }
    }

    /** Sleeps in small slices so a stop request takes effect within ~100 ms. */
    fun sleep(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (true) {
            check()
            val left = end - System.currentTimeMillis()
            if (left <= 0) return
            Thread.sleep(min(100L, left))
        }
    }
}

/** Randomness for human-like timing and touch positions. */
object Jitter {
    private val rnd = Random()

    /** Right-thumb rest: taps sit down-right of center, press path slants that way. Picked once per process. */
    val thumbX = gaussian(0.22f, 0.04f, 0.12f, 0.32f)
    val thumbY = gaussian(0.18f, 0.04f, 0.08f, 0.28f)

    fun range(a: Long, b: Long): Long = a + (rnd.nextDouble() * (b - a)).toLong()

    fun range(a: Float, b: Float): Float = a + rnd.nextFloat() * (b - a)

    fun gaussian(mean: Float, sd: Float, min: Float, max: Float): Float =
        (mean + rnd.nextGaussian().toFloat() * sd).coerceIn(min, max)

    fun gaussian(mean: Double, sd: Double, min: Long, max: Long): Long =
        (mean + rnd.nextGaussian() * sd).toLong().coerceIn(min, max)

    /** Log-normal around a median: mostly close to it, occasionally much longer. */
    fun logNormal(medianMs: Double, sigma: Double = 0.35): Long =
        (medianMs * exp(rnd.nextGaussian() * sigma)).toLong().coerceAtLeast(1L)

    /** Wait in [minMs, maxMs] with a log-normal bulk in the middle (not a flat range). */
    fun wait(minMs: Long, maxMs: Long): Long {
        val lo = minOf(minMs, maxMs).coerceAtLeast(1L)
        val hi = maxOf(minMs, maxMs)
        if (hi <= lo) return lo
        val median = sqrt(lo.toDouble() * hi.toDouble())
        val sigma = (ln(hi.toDouble() / lo.toDouble()) / 4.0).coerceAtLeast(0.12)
        return logNormal(median, sigma).coerceIn(lo, hi)
    }

    fun chance(p: Double): Boolean = rnd.nextDouble() < p

    /** One perceived key: a letter, or a whole emoji so it is not split mid-character. */
    fun graphemes(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val out = ArrayList<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            out.add(text.substring(start, end))
            start = end
            end = it.next()
        }
        return out
    }

    /** Pause after one key. ~40 WPM inside a word; slower after a space, so a message averages ~36 WPM. */
    fun keyPause(g: String): Long {
        if (g.isEmpty()) return logNormal(280.0, 0.4)
        val c = g[0]
        val median = when {
            g.length > 1 -> 520.0
            c == ' ' || c == '\n' -> 520.0
            c in ".!?,;:" -> 460.0
            c.isUpperCase() -> 340.0
            else -> 280.0
        }
        val think = if ((c == ' ' || c == '\n') && chance(0.15)) logNormal(650.0, 0.4) else 0L
        return logNormal(median, 0.4) + think
    }
}
