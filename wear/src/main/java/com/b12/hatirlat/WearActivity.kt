package com.b12.hatirlat

import android.app.Activity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView

/** Yuvarlak ekran boşlukları ve döner çerçeve (Galaxy Watch6 dokunmatik çerçevesi de bu olayı gönderir). */
open class WearActivity : Activity() {
    var rotary: ((Float) -> Unit)? = null

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_SCROLL && ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            val r = rotary
            if (r != null) {
                r(-ev.getAxisValue(MotionEvent.AXIS_SCROLL))
                return true
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    fun roundScroll(content: View): ScrollView {
        val w = resources.displayMetrics.widthPixels
        val side = (w * 0.11).toInt()
        content.setPadding(side, (w * 0.13).toInt(), side, (w * 0.24).toInt())
        val s = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            isVerticalScrollBarEnabled = true
            addView(content)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val factor = ViewConfiguration.get(this).scaledVerticalScrollFactor
        rotary = { d -> s.scrollBy(0, (d * factor).toInt()) }
        s.post { s.requestFocus() }
        return s
    }
}
