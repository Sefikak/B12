package com.b12.hatirlat

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Arayüz kodla kuruluyor; renkler ve küçük yardımcılar telefon ve saatte ortak. */
object Ui {
    val BG = 0xFF0F1216.toInt()
    val CARD = 0xFF1A1F26.toInt()
    val CARD2 = 0xFF252C35.toInt()
    val ACCENT = 0xFF8AB4FF.toInt()
    val ON_ACCENT = 0xFF0F1216.toInt()
    val TEXT = 0xFFECEFF4.toInt()
    val DIM = 0xFF96A0AE.toInt()
    val RED = 0xFFFF8080.toInt()
    val WARN = 0xFF3A2E14.toInt()
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).roundToInt()

fun Context.rounded(color: Int, radiusDp: Number, stroke: Int = 0): Drawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
    if (stroke != 0) setStroke(dp(1.5), stroke)
}

fun Context.pressable(color: Int, radiusDp: Number, stroke: Int = 0): Drawable =
    RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(color, radiusDp, stroke), rounded(0xFFFFFFFF.toInt(), radiusDp))

fun Context.label(text: CharSequence, sp: Number = 15, color: Int = Ui.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

/** Yuvarlak köşeli düğme. */
fun Context.pill(
    text: CharSequence,
    bg: Int = Ui.CARD2,
    fg: Int = Ui.TEXT,
    sp: Number = 14,
    padH: Number = 14,
    padV: Number = 9,
    onClick: (View) -> Unit,
): TextView = label(text, sp, fg, bold = true).apply {
    gravity = Gravity.CENTER
    background = pressable(bg, 100)
    setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    isClickable = true
    setOnClickListener(onClick)
}

fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

fun View.margins(ctx: Context, l: Number = 0, t: Number = 0, r: Number = 0, b: Number = 0): View {
    (layoutParams as? LinearLayout.LayoutParams ?: lp()).let {
        it.setMargins(ctx.dp(l), ctx.dp(t), ctx.dp(r), ctx.dp(b))
        layoutParams = it
    }
    return this
}

/** İlerleme çubuğu (0..1). */
fun Context.bar(frac: Float, heightDp: Number = 8, fg: Int = Ui.ACCENT, bg: Int = Ui.CARD2): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(bg, 100)
        val f = frac.coerceIn(0f, 1f)
        addView(View(context).apply { background = rounded(fg, 100) }, LinearLayout.LayoutParams(0, dp(heightDp), f))
        addView(View(context), LinearLayout.LayoutParams(0, dp(heightDp), 1f - f))
    }

/** Karttaki ✓ düğmesi: bugün bitti mi, kısmen mi? */
fun Context.checkButton(done: Int, total: Int, sizeDp: Int, onClick: () -> Unit): TextView {
    val full = total > 0 && done >= total
    val text = when {
        full -> "✓"
        total > 1 && done > 0 -> "$done/$total"
        else -> "✓"
    }
    return label(text, if (text.length > 1) 12 else 18, if (full) Ui.ON_ACCENT else Ui.ACCENT, bold = true).apply {
        gravity = Gravity.CENTER
        background = if (full) pressable(Ui.ACCENT, 100) else pressable(Ui.CARD, 100, Ui.ACCENT)
        alpha = if (total == 0) 0.35f else 1f
        contentDescription = "Yaptım"
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }
}

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}
