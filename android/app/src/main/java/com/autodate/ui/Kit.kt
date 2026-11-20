package com.autodate.ui

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** View builders shared by onboarding and Home, in the app's light palette. */
object Kit {
    fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun Context.label(s: CharSequence, size: Float, color: Int = Palette.TEXT, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(dp(2).toFloat(), 1f)
    }

    fun Context.title(s: CharSequence) = label(s, 27f, Palette.TEXT, bold = true).apply {
        letterSpacing = -0.01f
        setLineSpacing(0f, 1f)
    }

    fun Context.pill(s: CharSequence, fill: Int, ink: Int) = label(s, 12f, ink, bold = true).apply {
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = Palette.rounded(fill, dp(12).toFloat())
    }

    private fun Context.button(label: String, fill: Int, ink: Int, stroke: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(ink)
        stateListAnimator = null
        background = Palette.ripple(Palette.rounded(fill, dp(28).toFloat(), stroke, if (stroke != 0) dp(1) else 0))
        minHeight = dp(54)
        minimumHeight = dp(54)
        setOnClickListener { onClick() }
    }

    fun Context.primary(label: String, onClick: () -> Unit) =
        button(label, Palette.ACCENT, Palette.SURFACE, 0, onClick).apply { typeface = Typeface.DEFAULT_BOLD }

    fun Context.secondary(label: String, onClick: () -> Unit) =
        button(label, Palette.SURFACE, Palette.TEXT, Palette.BORDER, onClick)

    /** Text-only button, for "Skip" and "Back". */
    fun Context.quiet(label: String, color: Int = Palette.TEXT_2, onClick: () -> Unit) =
        button(label, 0x00000000, color, 0, onClick).apply { textSize = 15f; minHeight = dp(44); minimumHeight = dp(44) }

    fun Context.card(vararg children: View, fill: Int = Palette.SURFACE, pad: Int = 16) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        background = Palette.rounded(fill, dp(20).toFloat(), if (fill == Palette.SURFACE) Palette.BORDER else 0, if (fill == Palette.SURFACE) dp(1) else 0)
        children.forEach(::addView)
    }

    fun Context.row(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        gravity = Gravity.CENTER_VERTICAL
        children.forEach(::addView)
    }

    fun Context.field(hint: String, value: String, secret: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        textSize = 15f
        setTextColor(Palette.TEXT)
        setHintTextColor(Palette.TEXT_2)
        isSingleLine = true
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        typeface = Typeface.DEFAULT
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Palette.rounded(Palette.SURFACE, dp(14).toFloat(), Palette.ACCENT, dp(2)))
            addState(intArrayOf(), Palette.rounded(Palette.FIELD, dp(14).toFloat(), Palette.BORDER, dp(1)))
        }
        setPadding(dp(14), dp(14), dp(14), dp(14))
    }

    /** A numbered or icon bubble used in step lists. */
    fun Context.badge(s: String, fill: Int = Palette.ACCENT_SOFT, ink: Int = Palette.ACCENT, size: Int = 28) = label(s, 13f, ink, bold = true).apply {
        gravity = Gravity.CENTER
        background = Palette.rounded(fill, dp(size / 2).toFloat())
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    /** One line of a numbered list: badge, then text that wraps. */
    fun Context.stepLine(n: String, body: CharSequence, top: Int = 12) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        addView(badge(n))
        addView(label(body, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12); topMargin = dp(3) })
        layoutParams = lp(top)
    }

    fun Context.lp(top: Int = 0, width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(width, height).apply { topMargin = dp(top) }

    fun <T : View> T.mt(margin: Int): T = apply { layoutParams = context.lp(margin) }

    fun Context.space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
}
