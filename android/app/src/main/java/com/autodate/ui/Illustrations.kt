package com.autodate.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/*
 * Small looping illustrations drawn on a Canvas, used by onboarding and Home.
 * No assets and no libraries: each view redraws itself every frame while it's on screen, and
 * holds a still frame when the system has animations turned off (Remove animations / battery saver).
 */

/** Base for a looping drawing. [t] runs 0..1 over [periodMs]; [cycle] counts loops. */
abstract class LoopView(context: Context, private val periodMs: Long, private val stillAt: Float = 0.4f) : View(context) {
    private val startedAt = SystemClock.uptimeMillis()
    protected var t = 0f
    protected var cycle = 0
    protected val p = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val rect = RectF()
    protected val path = Path()

    protected fun dp(v: Float) = v * resources.displayMetrics.density
    protected fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    override fun onDraw(canvas: Canvas) {
        val moving = ValueAnimator.areAnimatorsEnabled()
        if (moving) {
            val elapsed = SystemClock.uptimeMillis() - startedAt
            cycle = (elapsed / periodMs).toInt()
            t = (elapsed % periodMs) / periodMs.toFloat()
        } else {
            cycle = 0
            t = stillAt
        }
        draw(canvas, width.toFloat(), height.toFloat())
        if (moving && isShown && isAttachedToWindow) postInvalidateOnAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }

    protected abstract fun draw(c: Canvas, w: Float, h: Float)

    // ---- shared drawing bits ----

    protected fun fill(color: Int) = p.apply { style = Paint.Style.FILL; shader = null; this.color = color; alpha = android.graphics.Color.alpha(color) }
    protected fun stroke(color: Int, width: Float) = p.apply { style = Paint.Style.STROKE; shader = null; this.color = color; strokeWidth = width; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    protected fun round(l: Float, t: Float, r: Float, b: Float, radius: Float, paint: Paint, c: Canvas) {
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, radius, radius, paint)
    }

    protected fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT, alpha: Float = 1f) {
        fill(color)
        p.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        p.textSize = size
        p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        p.textAlign = align
        c.drawText(s, x, y, p)
        p.alpha = 255
    }

    protected fun heart(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, alpha: Float = 1f) {
        path.reset()
        path.moveTo(cx, cy + s * 0.42f)
        path.cubicTo(cx - s * 1.05f, cy - s * 0.2f, cx - s * 0.5f, cy - s * 0.95f, cx, cy - s * 0.38f)
        path.cubicTo(cx + s * 0.5f, cy - s * 0.95f, cx + s * 1.05f, cy - s * 0.2f, cx, cy + s * 0.42f)
        path.close()
        fill(color)
        p.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        c.drawPath(path, p)
        p.alpha = 255
    }

    protected fun cross(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke(color, dp(2.5f))
        c.drawLine(cx - s, cy - s, cx + s, cy + s, p)
        c.drawLine(cx - s, cy + s, cx + s, cy - s, p)
    }

    protected fun check(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, width: Float = dp(2.5f), progress: Float = 1f) {
        stroke(color, width)
        path.reset()
        val ax = cx - s * 0.6f; val ay = cy
        val bx = cx - s * 0.15f; val by = cy + s * 0.45f
        val dx = cx + s * 0.7f; val dy = cy - s * 0.5f
        path.moveTo(ax, ay)
        val first = (progress / 0.4f).coerceIn(0f, 1f)
        path.lineTo(ax + (bx - ax) * first, ay + (by - ay) * first)
        if (progress > 0.4f) {
            val second = ((progress - 0.4f) / 0.6f).coerceIn(0f, 1f)
            path.lineTo(bx + (dx - bx) * second, by + (dy - by) * second)
        }
        c.drawPath(path, p)
    }

    /** A person-shaped "photo": soft gradient, head and shoulders, name bars. */
    protected fun profileCard(c: Canvas, l: Float, tp: Float, r: Float, b: Float, seed: Int, radius: Float) {
        val (top, bottom) = GRADIENTS[Math.floorMod(seed, GRADIENTS.size)]
        p.style = Paint.Style.FILL
        p.color = 0xFFFFFFFF.toInt() // a shader still uses the paint's alpha
        p.shader = LinearGradient(l, tp, r, b, top, bottom, Shader.TileMode.CLAMP)
        rect.set(l, tp, r, b)
        c.drawRoundRect(rect, radius, radius, p)
        p.shader = null
        val w = r - l; val h = b - tp
        val cx = l + w / 2
        fill(0xD9FFFFFF.toInt())
        c.drawCircle(cx, tp + h * 0.36f, w * 0.16f, p)
        rect.set(cx - w * 0.3f, tp + h * 0.55f, cx + w * 0.3f, tp + h * 0.95f)
        c.drawArc(rect, 180f, 180f, true, p)
        fill(0xF2FFFFFF.toInt())
        round(l + w * 0.1f, b - h * 0.2f, l + w * 0.6f, b - h * 0.2f + dp(5f), dp(3f), p, c)
        fill(0xB3FFFFFF.toInt())
        round(l + w * 0.1f, b - h * 0.2f + dp(9f), l + w * 0.42f, b - h * 0.2f + dp(13f), dp(2f), p, c)
    }

    /** A semi-clear fingertip, with a ripple when [tap] is 0..1. */
    protected fun finger(c: Canvas, x: Float, y: Float, tap: Float = -1f) {
        if (tap in 0f..1f) {
            fill(Palette.ACCENT)
            p.alpha = ((1 - tap) * 90).toInt()
            c.drawCircle(x, y, dp(10f) + dp(18f) * tap, p)
            p.alpha = 255
        }
        val press = if (tap in 0f..0.5f) 0.85f else 1f
        fill(0x59000000)
        c.drawCircle(x, y, dp(11f) * press, p)
        stroke(0xFFFFFFFF.toInt(), dp(2f))
        c.drawCircle(x, y, dp(11f) * press, p)
    }

    companion object {
        val GRADIENTS = listOf(
            0xFFFFB199.toInt() to 0xFFFF0844.toInt(),
            0xFFA18CD1.toInt() to 0xFFFBC2EB.toInt(),
            0xFF84FAB0.toInt() to 0xFF8FD3F4.toInt(),
            0xFFFAD0C4.toInt() to 0xFFF6A5C0.toInt(),
            0xFF89F7FE.toInt() to 0xFF66A6FF.toInt(),
        )

        fun seg(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
        fun easeInOut(x: Float) = if (x < 0.5f) 2 * x * x else 1 - (-2 * x + 2).let { it * it } / 2
        fun easeOut(x: Float) = 1 - (1 - x).let { it * it * it }
        fun easeIn(x: Float) = x * x * x
        fun overshoot(x: Float): Float {
            val c1 = 1.70158f; val c3 = c1 + 1
            return 1 + c3 * (x - 1).let { it * it * it } + c1 * (x - 1).let { it * it }
        }
        fun lerp(a: Float, b: Float, x: Float) = a + (b - a) * x
    }
}

/** Landing hero: a phone where profiles are read, scored, then liked or skipped. */
class SwipeDemoView(context: Context) : LoopView(context, 3400, stillAt = 0.42f) {
    override fun draw(c: Canvas, w: Float, h: Float) {
        val ph = h * 0.96f
        val pw = min(ph * 0.52f, w * 0.7f)
        val l = (w - pw) / 2; val tp = (h - ph) / 2
        val like = cycle % 3 != 2

        // soft glow behind the phone
        val glow = min(w, h) / 2
        fill(0x14D6336C)
        c.drawCircle(w / 2, h / 2, glow, p)
        fill(Palette.ACCENT_SOFT)
        c.drawCircle(w / 2, h / 2, glow * 0.8f, p)

        // phone body and screen
        fill(0xFF1C1C1E.toInt())
        round(l, tp, l + pw, tp + ph, pw * 0.14f, p, c)
        val inset = dp(5f)
        val sl = l + inset; val st = tp + inset; val sr = l + pw - inset; val sb = tp + ph - inset
        fill(0xFFF7F7F9.toInt())
        round(sl, st, sr, sb, pw * 0.11f, p, c)
        fill(0xFF1C1C1E.toInt())
        round(l + pw * 0.4f, st + dp(5f), l + pw * 0.6f, st + dp(10f), dp(3f), p, c)

        // card area and buttons
        val cl = sl + dp(8f); val cr = sr - dp(8f); val ct = st + dp(20f); val cb = sb - dp(52f)
        val radius = dp(12f)
        val by = sb - dp(27f)
        val skipX = sl + (sr - sl) * 0.3f; val likeX = sl + (sr - sl) * 0.7f

        // back card grows into place as the front card leaves
        val grow = easeInOut(seg(t, 0.55f, 0.85f))
        val s = lerp(0.9f, 1f, grow)
        c.save()
        c.scale(s, s, (cl + cr) / 2, cb)
        profileCard(c, cl, ct, cr, cb, cycle + 1, radius)
        c.restore()

        // front card
        val fly = easeIn(seg(t, 0.55f, 0.82f))
        val dir = if (like) 1 else -1
        c.save()
        c.translate(dir * fly * w * 0.75f, fly * dp(20f))
        c.rotate(dir * fly * 20f, (cl + cr) / 2, cb)
        profileCard(c, cl, ct, cr, cb, cycle, radius)

        // reading: a scan line sweeps the photo
        val scan = seg(t, 0.05f, 0.32f)
        if (scan in 0.001f..0.999f) {
            val y = lerp(ct + dp(6f), cb - dp(6f), easeInOut(scan))
            p.style = Paint.Style.FILL
            p.color = 0xFFFFFFFF.toInt()
            p.shader = LinearGradient(0f, y - dp(16f), 0f, y, 0x00FFFFFF, 0x99FFFFFF.toInt(), Shader.TileMode.CLAMP)
            rect.set(cl, y - dp(16f), cr, y)
            c.drawRect(rect, p)
            p.shader = null
            stroke(0xFFFFFFFF.toInt(), dp(2f))
            c.drawLine(cl + dp(4f), y, cr - dp(4f), y, p)
        }

        // the verdict badge
        val badge = seg(t, 0.32f, 0.44f)
        if (badge > 0f) {
            val bs = overshoot(badge)
            val label = if (like) "Like · ${88 + cycle % 9}" else "Skip · ${30 + cycle % 15}"
            val color = if (like) Palette.GOOD else Palette.TEXT_2
            p.textSize = sp(10f)
            p.typeface = Typeface.DEFAULT_BOLD
            val tw = p.measureText(label)
            val bx = (cl + cr) / 2; val bcy = ct + dp(18f)
            c.save()
            c.scale(bs, bs, bx, bcy)
            fill(0xFFFFFFFF.toInt())
            round(bx - tw / 2 - dp(8f), bcy - dp(10f), bx + tw / 2 + dp(8f), bcy + dp(10f), dp(10f), p, c)
            text(c, label, bx, bcy + sp(3.5f), sp(10f), color, bold = true, align = Paint.Align.CENTER)
            c.restore()
        }
        c.restore()

        // buttons, the chosen one presses
        val press = seg(t, 0.46f, 0.56f)
        val pressScale = 1f - 0.18f * sin(press * PI).toFloat()
        val r = dp(15f)
        listOf(skipX to false, likeX to true).forEach { (x, isLike) ->
            val chosen = isLike == like
            val sc = if (chosen) pressScale else 1f
            val lit = chosen && press > 0f && t < 0.9f
            fill(if (lit) (if (isLike) Palette.ACCENT else Palette.TEXT) else 0xFFFFFFFF.toInt())
            c.drawCircle(x, by, r * sc, p)
            stroke(0xFFE3E3E8.toInt(), dp(1f))
            c.drawCircle(x, by, r * sc, p)
            if (isLike) heart(c, x, by + dp(1f), dp(8f) * sc, if (lit) 0xFFFFFFFF.toInt() else Palette.ACCENT)
            else cross(c, x, by, dp(5f) * sc, if (lit) 0xFFFFFFFF.toInt() else Palette.TEXT_2)
        }

        // little hearts float up after a like
        if (like) {
            val burst = seg(t, 0.52f, 0.95f)
            if (burst in 0.001f..0.999f) {
                for (i in 0 until 5) {
                    val off = (i - 2) * dp(10f)
                    val rise = easeOut(burst) * dp(60f + i * 9f)
                    val wobble = sin((burst * 3 + i) * PI).toFloat() * dp(4f)
                    heart(c, likeX + off * burst + wobble, by - rise, dp(5f + (i % 3) * 1.5f), Palette.ACCENT, alpha = 1 - burst)
                }
            }
        }

        // the opener bubble, for likes
        val msg = seg(t, 0.62f, 0.72f)
        val msgOut = seg(t, 0.92f, 1f)
        if (like && msg > 0f) {
            p.textSize = sp(10.5f)
            p.typeface = Typeface.DEFAULT_BOLD
            val bw = p.measureText(OPENERS[Math.floorMod(cycle, OPENERS.size)]) + dp(20f)
            val bx = min(w / 2 + pw * 0.45f, w - dp(4f) - bw / 2)
            val byy = tp + ph * 0.12f
            val sc = overshoot(msg) * (1 - msgOut)
            c.save()
            c.scale(sc, sc, bx - bw / 2, byy + dp(14f))
            fill(Palette.ACCENT)
            round(bx - bw / 2, byy, bx + bw / 2, byy + dp(28f), dp(14f), p, c)
            text(c, OPENERS[Math.floorMod(cycle, OPENERS.size)], bx - bw / 2 + dp(10f), byy + dp(18f), sp(10.5f), 0xFFFFFFFF.toInt(), bold = true)
            c.restore()
        }
    }

    private companion object {
        val OPENERS = listOf("ok but which taco spot wins?", "that hike looks unreal", "settle it: best dumplings?", "teach me the guitar riff?")
    }
}

/** The three "how it works" steps, one per kind. */
class StepIllustration(context: Context, private val kind: Kind) : LoopView(context, 2800, stillAt = 0.7f) {
    enum class Kind { READ, DECIDE, WRITE }

    override fun draw(c: Canvas, w: Float, h: Float) {
        fill(Palette.ACCENT_SOFT)
        round(0f, 0f, w, h, dp(18f), p, c)
        when (kind) {
            Kind.READ -> read(c, w, h)
            Kind.DECIDE -> decide(c, w, h)
            Kind.WRITE -> write(c, w, h)
        }
    }

    private fun read(c: Canvas, w: Float, h: Float) {
        val l = w * 0.14f; val tp = h * 0.16f; val r = w * 0.5f; val b = h * 0.84f
        profileCard(c, l, tp, r, b, 1, dp(8f))
        val scan = easeInOut(seg(t, 0f, 0.55f))
        val y = lerp(tp + dp(3f), b - dp(3f), if (t < 0.55f) scan else 1 - easeInOut(seg(t, 0.55f, 1f)))
        stroke(0xFFFFFFFF.toInt(), dp(2f))
        c.drawLine(l + dp(3f), y, r - dp(3f), y, p)
        // text lines on the right "fill in" as they are read
        for (i in 0 until 4) {
            val ly = tp + dp(6f) + i * (b - tp - dp(12f)) / 3.5f
            val full = w * 0.86f - w * 0.58f - (if (i % 2 == 1) dp(10f) else 0f)
            fill(0xFFF0C6D5.toInt())
            round(w * 0.58f, ly, w * 0.58f + full, ly + dp(5f), dp(3f), p, c)
            val got = seg(t, 0.1f + i * 0.12f, 0.25f + i * 0.12f)
            if (got > 0f) {
                fill(Palette.ACCENT)
                round(w * 0.58f, ly, w * 0.58f + full * got, ly + dp(5f), dp(3f), p, c)
            }
        }
    }

    private fun decide(c: Canvas, w: Float, h: Float) {
        val cx = w / 2; val cy = h * 0.52f; val r = min(w, h) * 0.32f
        rect.set(cx - r, cy - r, cx + r, cy + r)
        stroke(0xFFF0C6D5.toInt(), dp(7f))
        c.drawArc(rect, 135f, 270f, false, p)
        val fillTo = easeOut(seg(t, 0.05f, 0.6f)) * (1 - seg(t, 0.92f, 1f))
        stroke(Palette.ACCENT, dp(7f))
        c.drawArc(rect, 135f, 270f * 0.88f * fillTo, false, p)
        text(c, "${(88 * fillTo).toInt()}", cx, cy + sp(6f), sp(19f), Palette.TEXT, bold = true, align = Paint.Align.CENTER)
        val verdict = seg(t, 0.6f, 0.7f) * (1 - seg(t, 0.9f, 1f))
        if (verdict > 0f) {
            val sc = overshoot(verdict)
            c.save()
            c.scale(sc, sc, cx, cy + r + dp(2f))
            fill(Palette.GOOD)
            round(cx - dp(20f), cy + r - dp(8f), cx + dp(20f), cy + r + dp(10f), dp(9f), p, c)
            text(c, "like", cx, cy + r + sp(4f), sp(10f), 0xFFFFFFFF.toInt(), bold = true, align = Paint.Align.CENTER)
            c.restore()
        }
    }

    private fun write(c: Canvas, w: Float, h: Float) {
        val l = w * 0.1f; val r = w * 0.9f; val tp = h * 0.26f; val b = h * 0.74f
        // their prompt, small and grey
        fill(0xFFFFFFFF.toInt())
        round(l, h * 0.1f, l + w * 0.5f, h * 0.1f + dp(14f), dp(7f), p, c)
        fill(0xFFE3E3E8.toInt())
        round(l + dp(6f), h * 0.1f + dp(5f), l + w * 0.42f, h * 0.1f + dp(9f), dp(2f), p, c)
        // your comment bubble
        fill(Palette.ACCENT)
        round(l + w * 0.08f, tp + dp(6f), r, b + dp(6f), dp(12f), p, c)
        val typing = t < 0.3f
        if (typing) {
            for (i in 0 until 3) {
                val bounce = sin(((t * 6) - i * 0.25f) * PI).toFloat().coerceAtLeast(0f)
                fill(0xFFFFFFFF.toInt())
                c.drawCircle((l + r) / 2 + w * 0.04f + (i - 1) * dp(9f), (tp + b) / 2 + dp(6f) - bounce * dp(4f), dp(3f), p)
            }
        } else {
            val shown = (LINES.sumOf { it.length } * easeOut(seg(t, 0.3f, 0.75f))).toInt()
            var left = shown
            LINES.forEachIndexed { i, line ->
                val part = line.take(left.coerceAtLeast(0))
                left -= line.length
                text(c, part, l + w * 0.08f + dp(9f), tp + dp(6f) + sp(14f) + i * sp(13f), sp(10.5f), 0xFFFFFFFF.toInt(), bold = true)
            }
            val sent = seg(t, 0.8f, 0.9f)
            if (sent > 0f) {
                fill(0xFFFFFFFF.toInt())
                c.drawCircle(r - dp(2f), b + dp(6f), dp(8f) * overshoot(sent), p)
                check(c, r - dp(2f), b + dp(6f), dp(4.5f), Palette.GOOD, dp(2f), seg(t, 0.82f, 0.95f))
            }
        }
    }

    private companion object {
        val LINES = listOf("ok but which", "taco spot wins?")
    }
}

/** A mock of the Android settings you have to change, with a finger showing where to tap. */
class SettingsDemoView(context: Context, private val kind: Kind) : LoopView(context, if (kind == Kind.ACCESSIBILITY) 6000 else 4800, stillAt = 0.9f) {
    enum class Kind { ACCESSIBILITY, RESTRICTED }

    private val screenBg = 0xFFFFFFFF.toInt()
    private val line = 0xFFEDEDF0.toInt()

    override fun draw(c: Canvas, w: Float, h: Float) {
        // a phone screen, cropped to the top part
        fill(0xFF1C1C1E.toInt())
        round(0f, 0f, w, h + dp(30f), dp(26f), p, c)
        c.save()
        val inset = dp(6f)
        rect.set(inset, inset, w - inset, h)
        path.reset()
        path.addRoundRect(rect, floatArrayOf(dp(21f), dp(21f), dp(21f), dp(21f), 0f, 0f, 0f, 0f), Path.Direction.CW)
        c.clipPath(path)
        fill(screenBg)
        c.drawRect(rect, p)
        if (kind == Kind.ACCESSIBILITY) accessibility(c, inset, w - inset, h) else restricted(c, inset, w - inset, h)
        c.restore()
    }

    private fun header(c: Canvas, l: Float, r: Float, title: String, alpha: Float = 1f) {
        // back arrow and title
        stroke(Palette.TEXT, dp(2f))
        p.alpha = (alpha * 255).toInt()
        val y = dp(36f)
        c.drawLine(l + dp(16f), y, l + dp(26f), y, p)
        c.drawLine(l + dp(16f), y, l + dp(21f), y - dp(5f), p)
        c.drawLine(l + dp(16f), y, l + dp(21f), y + dp(5f), p)
        p.alpha = 255
        text(c, title, l + dp(38f), y + sp(5.5f), sp(16f), Palette.TEXT, bold = true, alpha = alpha)
    }

    private fun row(c: Canvas, l: Float, r: Float, y: Float, title: String, sub: String?, highlight: Float = 0f, icon: Int? = null) {
        val rh = dp(46f)
        if (highlight > 0f) {
            fill(Palette.ACCENT_SOFT)
            p.alpha = (highlight * 255).toInt()
            c.drawRect(l, y, r, y + rh, p)
            p.alpha = 255
        }
        var tx = l + dp(16f)
        if (icon != null) {
            fill(icon)
            c.drawCircle(l + dp(28f), y + rh / 2, dp(12f), p)
            heart(c, l + dp(28f), y + rh / 2 + dp(1f), dp(6f), 0xFFFFFFFF.toInt())
            tx = l + dp(48f)
        }
        text(c, title, tx, y + (if (sub == null) rh / 2 + sp(5f) else dp(20f)), sp(13.5f), Palette.TEXT)
        if (sub != null) text(c, sub, tx, y + dp(36f), sp(11f), Palette.TEXT_2)
        fill(line)
        c.drawRect(l + dp(16f), y + rh - dp(1f), r, y + rh, p)
    }

    private fun toggle(c: Canvas, cx: Float, cy: Float, on: Float) {
        val tw = dp(34f); val th = dp(20f)
        val track = blend(0xFFC7C7CC.toInt(), Palette.ACCENT, on)
        fill(track)
        round(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2, th / 2, p, c)
        fill(0xFFFFFFFF.toInt())
        c.drawCircle(lerp(cx - tw / 2 + th / 2, cx + tw / 2 - th / 2, on), cy, th / 2 - dp(2.5f), p)
    }

    private fun accessibility(c: Canvas, l: Float, r: Float, h: Float) {
        val top = dp(64f)
        // Scene 1 (0..0.4): Accessibility → Downloaded apps
        // Scene 2 (0.4..1): Use autoDate toggle → Allow
        val slide = easeInOut(seg(t, 0.36f, 0.46f))
        val width = r - l
        val tapRow = seg(t, 0.24f, 0.34f)

        c.save()
        c.translate(-slide * width, 0f)
        header(c, l, r, "Accessibility")
        row(c, l, r, top, "TalkBack", "Off")
        row(c, l, r, top + dp(46f), "Downloaded apps", "autoDate", highlight = if (tapRow > 0f) 1 - tapRow * 0.3f else 0f)
        row(c, l, r, top + dp(92f), "Display size and text", null)
        c.restore()

        c.save()
        c.translate((1 - slide) * width, 0f)
        header(c, l, r, "autoDate")
        val on = easeInOut(seg(t, 0.58f, 0.64f))
        row(c, l, r, top, "Use autoDate", null)
        toggle(c, r - dp(32f), top + dp(23f), on)
        text(c, "Reads Hinge's screen and taps", l + dp(16f), top + dp(70f), sp(11f), Palette.TEXT_2)
        text(c, "for you. Only works in Hinge.", l + dp(16f), top + dp(86f), sp(11f), Palette.TEXT_2)
        c.restore()

        // Android's "Allow full control?" dialog
        val dlg = seg(t, 0.66f, 0.72f) * (1 - seg(t, 0.9f, 0.95f))
        if (dlg > 0f) {
            fill(0x66000000)
            p.alpha = (dlg * 102).toInt()
            c.drawRect(l, 0f, r, h, p)
            p.alpha = 255
            val dw = width * 0.82f; val dl = l + (width - dw) / 2; val dt = h * 0.32f
            val sc = lerp(0.9f, 1f, easeOut(dlg))
            c.save()
            c.scale(sc, sc, l + width / 2, dt + dp(50f))
            fill(0xFFFFFFFF.toInt())
            p.alpha = (dlg * 255).toInt()
            round(dl, dt, dl + dw, dt + dp(104f), dp(16f), p, c)
            p.alpha = 255
            text(c, "Allow autoDate to have", dl + dp(16f), dt + dp(26f), sp(12.5f), Palette.TEXT, bold = true, alpha = dlg)
            text(c, "full control of your device?", dl + dp(16f), dt + dp(43f), sp(12.5f), Palette.TEXT, bold = true, alpha = dlg)
            val pressed = seg(t, 0.82f, 0.86f)
            fill(if (pressed > 0f) Palette.ACCENT else Palette.ACCENT_SOFT)
            p.alpha = (dlg * 255).toInt()
            round(dl + dp(16f), dt + dp(60f), dl + dw - dp(16f), dt + dp(90f), dp(15f), p, c)
            p.alpha = 255
            text(c, "Allow", dl + dw / 2, dt + dp(80f), sp(12.5f), if (pressed > 0f) 0xFFFFFFFF.toInt() else Palette.ACCENT, bold = true, align = Paint.Align.CENTER, alpha = dlg)
            c.restore()
        }

        // the finger
        val restX = r - dp(30f); val restY = h - dp(18f)
        val rowX = l + width * 0.5f; val rowY = top + dp(69f)
        val togX = r - dp(32f); val togY = top + dp(23f)
        val allowX = l + width / 2; val allowY = h * 0.32f + dp(75f)
        val (fx, fy, tap) = when {
            t < 0.08f -> Triple(restX, restY, -1f)
            t < 0.24f -> easeInOut(seg(t, 0.08f, 0.24f)).let { Triple(lerp(restX, rowX, it), lerp(restY, rowY, it), -1f) }
            t < 0.36f -> Triple(rowX, rowY, seg(t, 0.24f, 0.34f))
            t < 0.46f -> Triple(rowX, rowY, -1f)
            t < 0.56f -> easeInOut(seg(t, 0.46f, 0.56f)).let { Triple(lerp(rowX, togX, it), lerp(rowY, togY, it), -1f) }
            t < 0.66f -> Triple(togX, togY, seg(t, 0.56f, 0.64f))
            t < 0.78f -> easeInOut(seg(t, 0.68f, 0.78f)).let { Triple(lerp(togX, allowX, it), lerp(togY, allowY, it), -1f) }
            t < 0.9f -> Triple(allowX, allowY, seg(t, 0.8f, 0.88f))
            else -> easeInOut(seg(t, 0.9f, 1f)).let { Triple(lerp(allowX, restX, it), lerp(allowY, restY, it), -1f) }
        }
        val fade = 1 - seg(t, 0.94f, 1f)
        if (fade > 0f) finger(c, fx, fy, tap)
    }

    private fun restricted(c: Canvas, l: Float, r: Float, h: Float) {
        val width = r - l
        header(c, l, r, "App info")
        // ⋮ menu button
        val dotsX = r - dp(22f); val dotsY = dp(36f)
        fill(Palette.TEXT)
        for (i in -1..1) c.drawCircle(dotsX, dotsY + i * dp(5f), dp(2f), p)
        // the app
        fill(Palette.ACCENT)
        c.drawCircle(l + width / 2, dp(92f), dp(20f), p)
        heart(c, l + width / 2, dp(94f), dp(10f), 0xFFFFFFFF.toInt())
        text(c, "autoDate", l + width / 2, dp(132f), sp(14f), Palette.TEXT, bold = true, align = Paint.Align.CENTER)
        fill(line)
        round(l + dp(16f), dp(150f), r - dp(16f), dp(156f), dp(3f), p, c)
        round(l + dp(16f), dp(166f), r - dp(60f), dp(172f), dp(3f), p, c)

        // the menu
        val menu = seg(t, 0.26f, 0.34f) * (1 - seg(t, 0.62f, 0.68f))
        if (menu > 0f) {
            val mw = width * 0.72f
            val ml = r - dp(8f) - mw; val mt = dp(22f)
            c.save()
            c.scale(lerp(0.85f, 1f, easeOut(menu)), lerp(0.85f, 1f, easeOut(menu)), r - dp(8f), mt)
            fill(0x22000000)
            p.alpha = (menu * 34).toInt()
            round(ml + dp(1f), mt + dp(2f), r - dp(7f), mt + dp(92f) + dp(3f), dp(10f), p, c)
            fill(0xFFFFFFFF.toInt())
            p.alpha = (menu * 255).toInt()
            round(ml, mt, r - dp(8f), mt + dp(92f), dp(10f), p, c)
            p.alpha = 255
            text(c, "Uninstall", ml + dp(14f), mt + dp(28f), sp(12.5f), Palette.TEXT, alpha = menu)
            val hl = seg(t, 0.5f, 0.56f)
            if (hl > 0f) {
                fill(Palette.ACCENT_SOFT)
                p.alpha = (menu * 255).toInt()
                c.drawRect(ml, mt + dp(46f), r - dp(8f), mt + dp(84f), p)
                p.alpha = 255
            }
            text(c, "Allow restricted", ml + dp(14f), mt + dp(62f), sp(12.5f), Palette.TEXT, bold = true, alpha = menu)
            text(c, "settings", ml + dp(14f), mt + dp(78f), sp(12.5f), Palette.TEXT, bold = true, alpha = menu)
            c.restore()
        }

        // done: a small confirmation
        val done = seg(t, 0.68f, 0.76f) * (1 - seg(t, 0.94f, 1f))
        if (done > 0f) {
            val bw = width * 0.7f; val bl = l + (width - bw) / 2; val bt = h - dp(48f)
            fill(0xFF1C1C1E.toInt())
            p.alpha = (done * 230).toInt()
            round(bl, bt, bl + bw, bt + dp(30f), dp(15f), p, c)
            p.alpha = 255
            check(c, bl + dp(18f), bt + dp(15f), dp(6f), 0xFF7EE29A.toInt(), dp(2f), seg(t, 0.7f, 0.8f))
            text(c, "Restricted settings allowed", bl + dp(30f), bt + dp(19.5f), sp(10.5f), 0xFFFFFFFF.toInt(), bold = true, alpha = done)
        }

        // the finger: ⋮, then the menu item
        val restX = l + width * 0.6f; val restY = h - dp(10f)
        val itemX = r - width * 0.36f; val itemY = dp(22f) + dp(65f)
        val (fx, fy, tap) = when {
            t < 0.06f -> Triple(restX, restY, -1f)
            t < 0.2f -> easeInOut(seg(t, 0.06f, 0.2f)).let { Triple(lerp(restX, dotsX, it), lerp(restY, dotsY, it), -1f) }
            t < 0.32f -> Triple(dotsX, dotsY, seg(t, 0.2f, 0.3f))
            t < 0.46f -> easeInOut(seg(t, 0.34f, 0.46f)).let { Triple(lerp(dotsX, itemX, it), lerp(dotsY, itemY, it), -1f) }
            t < 0.62f -> Triple(itemX, itemY, seg(t, 0.5f, 0.6f))
            else -> easeInOut(seg(t, 0.62f, 0.8f)).let { Triple(lerp(itemX, restX, it), lerp(itemY, restY, it), -1f) }
        }
        if (t < 0.85f) finger(c, fx, fy, tap)
    }

    private fun blend(a: Int, b: Int, x: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * x).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** Finish line: a heart with a check, and hearts drifting up. */
class CelebrateView(context: Context) : LoopView(context, 3600, stillAt = 0.5f) {
    private val born = SystemClock.uptimeMillis()

    override fun draw(c: Canvas, w: Float, h: Float) {
        val cx = w / 2; val cy = h * 0.55f
        // drifting hearts, each on its own clock
        for (i in 0 until 14) {
            val period = 2600f + (i * 437) % 1500
            val phase = ((SystemClock.uptimeMillis() + i * 911) % period.toLong()) / period
            val x = w * (((i * 53) % 100) / 100f) + sin((phase * 2 + i) * PI).toFloat() * dp(10f)
            val y = h - phase * (h + dp(20f))
            val a = (if (phase < 0.2f) phase / 0.2f else 1f) * (1 - phase) * 0.6f
            heart(c, x, y, dp(5f + (i % 4) * 2f), if (i % 3 == 0) 0xFFF6A5C0.toInt() else Palette.ACCENT, alpha = a)
        }
        val intro = seg(((SystemClock.uptimeMillis() - born) / 700f).coerceAtMost(1f), 0f, 1f)
        val beat = 1f + 0.05f * sin(t * 2 * PI).toFloat().let { abs(it) }
        val s = overshoot(intro) * beat
        fill(0x1FD6336C)
        c.drawCircle(cx, cy, min(w, h) * 0.34f * s, p)
        heart(c, cx, cy + dp(6f) * s, min(w, h) * 0.22f * s, Palette.ACCENT)
        check(c, cx, cy - dp(2f) * s, min(w, h) * 0.08f * s, 0xFFFFFFFF.toInt(), dp(4f), seg(((SystemClock.uptimeMillis() - born) / 1100f), 0.45f, 1f))
    }
}

/**
 * Home's big round button. [state] picks the look: ready rings pulse outward, running shows a
 * stop square, blocked is greyed out.
 */
class StartOrb(context: Context) : LoopView(context, 2400, stillAt = 0.3f) {
    enum class State { READY, RUNNING, BLOCKED }

    var state = State.READY
        set(v) { field = v; invalidate() }
    var label = "Start"
        set(v) { field = v; contentDescription = v; invalidate() }
    var sublabel = ""
        set(v) { field = v; invalidate() }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = label
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        return super.onTouchEvent(event)
    }

    override fun draw(c: Canvas, w: Float, h: Float) {
        val cx = w / 2; val cy = h / 2
        val r = min(w, h) * 0.36f
        val (top, bottom) = when (state) {
            State.READY -> 0xFFEC4B86.toInt() to 0xFFC2255C.toInt()
            State.RUNNING -> 0xFF3A3A40.toInt() to 0xFF1C1C1E.toInt()
            State.BLOCKED -> 0xFFD1D1D6.toInt() to 0xFFAEAEB2.toInt()
        }
        if (state != State.BLOCKED) {
            for (i in 0 until 2) {
                val ph = (t + i * 0.5f) % 1f
                fill(if (state == State.READY) Palette.ACCENT else 0xFF1C1C1E.toInt())
                p.alpha = ((1 - ph) * (if (state == State.READY) 60 else 40)).toInt()
                c.drawCircle(cx, cy, r * (1f + 0.38f * easeOut(ph)), p)
                p.alpha = 255
            }
        }
        fill(0x1A000000)
        c.drawCircle(cx, cy + dp(4f), r, p)
        p.style = Paint.Style.FILL
        p.color = 0xFFFFFFFF.toInt()
        p.shader = LinearGradient(cx, cy - r, cx, cy + r, top, bottom, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null

        val ink = if (state == State.BLOCKED) 0xFF6B6B73.toInt() else 0xFFFFFFFF.toInt()
        val iconY = cy - r * 0.28f
        when (state) {
            State.RUNNING -> { fill(ink); round(cx - dp(9f), iconY - dp(9f), cx + dp(9f), iconY + dp(9f), dp(3f), p, c) }
            else -> {
                fill(ink)
                path.reset()
                path.moveTo(cx - dp(7f), iconY - dp(11f))
                path.lineTo(cx + dp(11f), iconY)
                path.lineTo(cx - dp(7f), iconY + dp(11f))
                path.close()
                c.drawPath(path, p)
            }
        }
        text(c, label, cx, cy + r * 0.22f, sp(19f), ink, bold = true, align = Paint.Align.CENTER)
        if (sublabel.isNotEmpty()) text(c, sublabel, cx, cy + r * 0.22f + sp(18f), sp(11.5f), ink, align = Paint.Align.CENTER, alpha = 0.85f)
    }
}

/** A ring that fills to [fraction], with a big number in the middle. Animates when the value changes. */
class RingStat(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var shown = 0f
    var value = "–"
        set(v) { field = v; invalidate() }
    var fraction = 0f
        set(v) {
            val target = v.coerceIn(0f, 1f)
            if (target == field) return
            field = target
            ValueAnimator.ofFloat(shown, target).apply {
                duration = 700
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            }.start()
        }
    var color = Palette.ACCENT

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val s = min(width, height).toFloat()
        val sw = 6 * d
        rect.set((width - s) / 2 + sw / 2, (height - s) / 2 + sw / 2, (width + s) / 2 - sw / 2, (height + s) / 2 - sw / 2)
        p.style = Paint.Style.STROKE
        p.strokeWidth = sw
        p.strokeCap = Paint.Cap.ROUND
        p.color = Palette.BORDER
        c.drawArc(rect, 0f, 360f, false, p)
        p.color = color
        if (shown > 0f) c.drawArc(rect, -90f, 360f * shown, false, p)
        p.style = Paint.Style.FILL
        p.color = Palette.TEXT
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, if (value.length > 4) 13f else 16f, resources.displayMetrics)
        c.drawText(value, width / 2f, height / 2f + p.textSize * 0.35f, p)
    }
}

/** Bottom-bar icons, drawn as simple strokes. */
class NavIcon(context: Context, private val kind: Kind) : View(context) {
    enum class Kind { HOME, HISTORY, REVIEW, SETTINGS }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    var active = false
        set(v) { field = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f; val cy = height / 2f
        if (active) {
            p.style = Paint.Style.FILL
            p.color = Palette.ACCENT_SOFT
            rect.set(cx - 28 * d, cy - 15 * d, cx + 28 * d, cy + 15 * d)
            c.drawRoundRect(rect, 15 * d, 15 * d, p)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * d
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.color = if (active) Palette.ACCENT else Palette.TEXT_2
        val s = 9 * d
        path.reset()
        when (kind) {
            Kind.HOME -> {
                path.moveTo(cx - s, cy - s * 0.1f)
                path.lineTo(cx, cy - s)
                path.lineTo(cx + s, cy - s * 0.1f)
                path.moveTo(cx - s * 0.75f, cy - s * 0.3f)
                path.lineTo(cx - s * 0.75f, cy + s * 0.9f)
                path.lineTo(cx + s * 0.75f, cy + s * 0.9f)
                path.lineTo(cx + s * 0.75f, cy - s * 0.3f)
                c.drawPath(path, p)
            }
            Kind.HISTORY -> {
                c.drawCircle(cx, cy, s * 0.95f, p)
                c.drawLine(cx, cy, cx, cy - s * 0.55f, p)
                c.drawLine(cx, cy, cx + s * 0.45f, cy + s * 0.3f, p)
            }
            Kind.REVIEW -> {
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) s else s * 0.45f
                    val a = Math.toRadians((i * 36 - 90).toDouble())
                    val x = cx + (r * Math.cos(a)).toFloat(); val y = cy + (r * Math.sin(a)).toFloat() + s * 0.08f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                c.drawPath(path, p)
            }
            Kind.SETTINGS -> {
                listOf(-0.6f to 0.35f, 0f to -0.4f, 0.6f to 0.15f).forEach { (dy, knob) ->
                    c.drawLine(cx - s, cy + dy * s, cx + s, cy + dy * s, p)
                    p.style = Paint.Style.FILL
                    p.color = if (active) Palette.ACCENT_SOFT else Palette.SURFACE
                    c.drawCircle(cx + knob * s, cy + dy * s, s * 0.28f, p)
                    p.style = Paint.Style.STROKE
                    p.color = if (active) Palette.ACCENT else Palette.TEXT_2
                    c.drawCircle(cx + knob * s, cy + dy * s, s * 0.28f, p)
                }
            }
        }
    }
}
