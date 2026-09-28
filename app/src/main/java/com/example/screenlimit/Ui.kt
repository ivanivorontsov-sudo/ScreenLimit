package com.example.screenlimit

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/** Мини-DSL для программной вёрстки в стиле Material 3. */
fun Context.dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun Activity.scrollPage(build: LinearLayout.() -> Unit): LinearLayout {
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(32))
    }
    col.build()
    val sv = ScrollView(this).apply { isFillViewport = true; addView(col) }
    setContentView(sv)
    return col
}

fun LinearLayout.text(t: CharSequence, sizeSp: Float = 16f, bold: Boolean = false, color: Int? = null, gravity: Int = Gravity.START, topDp: Int = 4): TextView =
    TextView(context).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextColor(color ?: ContextCompat.getColor(context, R.color.on_surface))
        this.gravity = gravity
        setLineSpacing(0f, 1.15f)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(topDp) }
    }.also { addView(it) }

fun LinearLayout.title(t: CharSequence) = text(t, 22f, bold = true, topDp = 8)
fun LinearLayout.section(t: CharSequence) = text(t, 13f, bold = true, color = ContextCompat.getColor(context, R.color.primary), topDp = 20)

fun LinearLayout.button(t: CharSequence, style: Int = 0, onClick: (View) -> Unit): MaterialButton {
    val attr = when (style) { 1 -> com.google.android.material.R.attr.materialButtonOutlinedStyle; 2 -> com.google.android.material.R.attr.borderlessButtonStyle; else -> com.google.android.material.R.attr.materialButtonStyle }
    return MaterialButton(context, null, attr).apply {
        text = t; isAllCaps = false; setOnClickListener(onClick)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(6) }
    }.also { addView(it) }
}

fun LinearLayout.card(build: LinearLayout.() -> Unit): MaterialCardView {
    val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(16)) }
    inner.build()
    return MaterialCardView(context).apply {
        radius = context.dp(16).toFloat(); cardElevation = 0f
        setCardBackgroundColor(ContextCompat.getColor(context, R.color.surface_variant))
        strokeWidth = 0
        addView(inner)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(12) }
    }.also { addView(it) }
}

fun LinearLayout.row(build: LinearLayout.() -> Unit): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(4) }
    build()
}.also { addView(it) }

fun View.weight(w: Float = 1f): View { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).apply { marginEnd = context.dp(4) }; return this }
