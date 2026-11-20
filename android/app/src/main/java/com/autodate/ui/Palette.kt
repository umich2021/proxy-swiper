package com.autodate.ui

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/** Light palette shared by the app screen and the overlay. Mirrors res/values/colors.xml. */
object Palette {
    const val BG = 0xFFF5F5F7.toInt()
    const val SURFACE = 0xFFFFFFFF.toInt()
    const val FIELD = 0xFFF5F5F7.toInt()
    const val BORDER = 0xFFE3E3E8.toInt()
    const val TEXT = 0xFF1C1C1E.toInt()
    const val TEXT_2 = 0xFF6B6B73.toInt()
    const val ACCENT = 0xFFD6336C.toInt()
    const val ACCENT_SOFT = 0xFFFCE8EF.toInt()
    const val GOOD = 0xFF1E7B34.toInt()
    const val GOOD_SOFT = 0xFFE3F4E7.toInt()
    const val BAD = 0xFFC62828.toInt()
    const val BAD_SOFT = 0xFFFDE7E7.toInt()
    const val RIPPLE = 0x1F000000

    fun rounded(fill: Int, radius: Float, stroke: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(fill)
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

    /** [shape] with a touch ripple clipped to it. */
    fun ripple(shape: GradientDrawable): Drawable = RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, null)
}
