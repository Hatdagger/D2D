package com.hatdagger.discdroid.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** The app's colours. Dark, with the DS-style blue (rip), red (burn) and green (player) accents. */
object Palette {
    const val BG_TOP = 0xFF0A0E15.toInt()
    const val BG_BOTTOM = 0xFF121925.toInt()
    const val SURFACE = 0xFF161D2A.toInt()
    const val SURFACE_HI = 0xFF1F2839.toInt()
    const val TEXT = 0xFFE9EEF6.toInt()
    const val TEXT_DIM = 0xFF8693A6.toInt()
    const val LOG = 0xFFA7B4C6.toInt()
    const val ON_ACCENT = 0xFF0A0E15.toInt()
    const val BLUE = 0xFF4C9BFF.toInt()
    const val RED = 0xFFFF5A5F.toInt()
    const val GREEN = 0xFF2FD39A.toInt()
    const val AMBER = 0xFFFFB547.toInt()

    fun alpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))
}

/** Small factory for styled views, so the screen code stays readable. */
class Ui(private val ctx: Context) {
    private val density = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()

    enum class Style { FILLED, TONAL, OUTLINE, GHOST }

    fun rounded(fill: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
    }

    fun ripple(content: Drawable, accent: Int): Drawable =
        RippleDrawable(ColorStateList.valueOf(Palette.alpha(accent, 70)), content, rounded(Color.WHITE, 22))

    fun text(s: String = "", sizeSp: Float = 14f, color: Int = Palette.TEXT, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun button(label: String, accent: Int, style: Style = Style.TONAL, onClick: () -> Unit): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null
        elevation = 0f
        minHeight = dp(44); minimumHeight = dp(44)
        minWidth = 0; minimumWidth = 0
        setPadding(dp(14), dp(6), dp(14), dp(6))
        applyStyle(this, accent, style)
        setOnClickListener { onClick() }
    }

    fun applyStyle(b: Button, accent: Int, style: Style) {
        val fill: Int; val stroke: Int; val fg: Int
        when (style) {
            Style.FILLED -> { fill = accent; stroke = 0; fg = Palette.ON_ACCENT }
            Style.TONAL -> { fill = Palette.alpha(accent, 40); stroke = 0; fg = Palette.TEXT }
            Style.OUTLINE -> { fill = Color.TRANSPARENT; stroke = Palette.alpha(accent, 150); fg = accent }
            Style.GHOST -> { fill = Color.TRANSPARENT; stroke = 0; fg = Palette.TEXT_DIM }
        }
        b.background = ripple(rounded(fill, 22, stroke, if (stroke != 0) 1 else 0), accent)
        b.setTextColor(fg)
    }

    /** A rounded card with a coloured edge. */
    fun card(accent: Int): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = cardBackground(accent)
        setPadding(dp(14), dp(12), dp(14), dp(10))
    }

    fun cardBackground(accent: Int): Drawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Palette.alpha(accent, 26), Palette.SURFACE),
    ).apply {
        cornerRadius = dp(20).toFloat()
        setStroke(dp(1), Palette.alpha(accent, 70))
    }

    /** Section badge such as "RIP", with a one-line description next to it. */
    fun sectionTitle(badge: String, accent: Int, subtitle: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text(badge, 12f, Palette.ON_ACCENT, bold = true).apply {
            letterSpacing = 0.12f
            background = rounded(accent, 10)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        })
        addView(text(subtitle, 13f, Palette.TEXT_DIM).apply { setPadding(dp(10), 0, 0, 0) })
    }

    fun toggle(label: String, accent: Int, checked: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(ctx).apply {
        text = label
        textSize = 13f
        setTextColor(Palette.TEXT)
        isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(accent, 0xFFB8C2D0.toInt()))
        trackTintList = ColorStateList(states, intArrayOf(Palette.alpha(accent, 110), 0xFF3A4558.toInt()))
        setPadding(0, dp(4), dp(12), dp(4))
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }

    fun tint(p: ProgressBar, accent: Int) {
        p.progressTintList = ColorStateList.valueOf(accent)
        p.progressBackgroundTintList = ColorStateList.valueOf(Palette.SURFACE_HI)
    }

    fun tint(s: SeekBar, accent: Int) {
        s.progressTintList = ColorStateList.valueOf(accent)
        s.thumbTintList = ColorStateList.valueOf(accent)
        s.progressBackgroundTintList = ColorStateList.valueOf(0xFF3A4558.toInt())
    }

    /** Row of views sharing the width equally, with small gaps. */
    fun row(vararg views: View, gapDp: Int = 8): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for ((i, v) in views.withIndex()) {
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(gapDp)
            })
        }
    }

    /** Segmented control: one of [labels] is selected; [accents] colours each segment. */
    inner class Segments(labels: List<String>, private val accents: List<Int>, private val onSelect: (Int) -> Unit) {
        val view = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Palette.SURFACE_HI, 22)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        private val items = labels.mapIndexed { i, l ->
            text(l, 14f, Palette.TEXT_DIM, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { select(i); onSelect(i) }
                view.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        var selected = 0
            private set

        fun select(i: Int) {
            selected = i
            for ((j, t) in items.withIndex()) {
                if (j == i) { t.background = rounded(accents[j], 19); t.setTextColor(Palette.ON_ACCENT) }
                else { t.background = null; t.setTextColor(Palette.TEXT_DIM) }
            }
        }
    }
}
