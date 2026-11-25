package com.b12.hatirlat

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.NumberPicker

/** Saat seçimi: kaydırarak ya da çerçeveyi çevirerek. Dokunulan sütunu çerçeve çevirir. */
class TimeActivity : WearActivity() {
    private lateinit var hour: NumberPicker
    private lateinit var minute: NumberPicker
    private var active: NumberPicker? = null
    private var acc = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val m = intent.getIntExtra("m", 9 * 60)
        hour = NumberPicker(this).apply {
            minValue = 0; maxValue = 23
            setFormatter { "%02d".format(it) }
            value = m / 60
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            big(this)
        }
        minute = NumberPicker(this).apply {
            minValue = 0; maxValue = 11
            displayedValues = Array(12) { "%02d".format(it * 5) }
            value = ((m % 60) + 2) / 5 % 12
            wrapSelectorWheel = true
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            big(this)
        }
        active = hour
        hour.setOnScrollListener { _, _ -> active = hour; mark() }
        minute.setOnScrollListener { _, _ -> active = minute; mark() }
        hour.setOnValueChangedListener { _, _, _ -> active = hour; mark() }
        minute.setOnValueChangedListener { _, _, _ -> active = minute; mark() }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(hour, lp(dp(64), dp(130)))
            addView(label(":", 22, Ui.TEXT, bold = true).apply { setPadding(dp(6), 0, dp(6), 0) })
            addView(minute, lp(dp(64), dp(130)))
        }
        val c = vbox().apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.BG)
            addView(row)
            addView(pill("Tamam", Ui.ACCENT, Ui.ON_ACCENT, 14) {
                setResult(RESULT_OK, Intent().putExtra("m", hour.value * 60 + minute.value * 5))
                finish()
            }, lp(LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(4) })
        }
        setContentView(c)
        mark()
        rotary = { d ->
            acc += d
            val p = active ?: hour
            while (acc >= 1f) { acc -= 1f; p.value = if (p.value == p.maxValue) p.minValue else p.value + 1 }
            while (acc <= -1f) { acc += 1f; p.value = if (p.value == p.minValue) p.maxValue else p.value - 1 }
        }
    }

    private fun big(p: NumberPicker) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            p.textSize = dp(26).toFloat()
            p.textColor = Ui.TEXT
        }
        p.setOnTouchListener { _, _ -> active = p; mark(); false }
    }

    private fun mark() {
        hour.alpha = if (active == hour) 1f else 0.55f
        minute.alpha = if (active == minute) 1f else 0.55f
    }
}
