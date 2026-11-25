package com.b12.hatirlat

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class EditActivity : WearActivity() {
    private lateinit var base: Reminder
    private var isNew = true
    private var days = Reminder.ALL_DAYS
    private val times = mutableListOf<Int>()
    private var minutes = 0
    private var enabled = true
    private var nag = true
    private var nagMin = 15

    private lateinit var title: EditText
    private lateinit var dayRows: LinearLayout
    private lateinit var timeList: LinearLayout
    private lateinit var durText: TextView
    private lateinit var onBtn: TextView
    private lateinit var nagBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val existing = intent.getStringExtra("id")?.let { Store.get(this, it) }
        isNew = existing == null
        base = existing ?: Reminder(nag = true)
        days = base.days
        times.addAll(base.times.sorted())
        minutes = base.minutes
        enabled = base.enabled
        nag = base.nag
        nagMin = base.nagMin
        savedInstanceState?.let { s ->
            days = s.getInt("days", days)
            times.clear(); times.addAll(s.getIntArray("times")?.toList() ?: base.times)
            minutes = s.getInt("minutes", minutes)
            enabled = s.getBoolean("enabled", enabled)
            nag = s.getBoolean("nag", nag)
            nagMin = s.getInt("nagMin", nagMin)
        }

        val c = vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusableInTouchMode = true   // açılışta klavye kendiliğinden açılmasın
        }
        c.addView(label(if (isNew) "Yeni" else "Düzenle", 13, Ui.ACCENT, bold = true).apply { gravity = Gravity.CENTER }, lp().also { it.bottomMargin = dp(6) })
        title = EditText(this).apply {
            setText(base.title)
            hint = "Ne hatırlatayım?"
            setHintTextColor(Ui.DIM)
            setTextColor(Ui.TEXT)
            textSize = 15f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = rounded(Ui.CARD, 20)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        c.addView(title, lp())

        c.addView(section("Günler"))
        dayRows = vbox()
        c.addView(dayRows, lp())
        c.addView(pill("Her gün", Ui.CARD, Ui.TEXT, 12, 10, 5) { days = Reminder.ALL_DAYS; renderDays() },
            lp(LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(6); it.gravity = Gravity.CENTER_HORIZONTAL })

        c.addView(section("Saatler"))
        timeList = vbox()
        c.addView(timeList, lp())
        c.addView(pill("+ Saat", Ui.CARD, Ui.ACCENT, 13) { pick(-1, times.maxOrNull()?.let { (it + 60) % 1440 } ?: (9 * 60)) }, lp())

        c.addView(section("Süre"))
        val durRow = hbox()
        durRow.addView(step("−") { minutes = (minutes - 15).coerceAtLeast(0); renderDur() })
        durText = label("", 13, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER }
        durRow.addView(durText, lp(0, weight = 1f))
        durRow.addView(step("+") { minutes = (minutes + 15).coerceAtMost(240); renderDur() })
        c.addView(durRow, lp())

        c.addView(section("Yapana kadar"))
        nagBtn = pill("", Ui.CARD, Ui.TEXT, 13) {
            // Kapalı → 5 → 10 → 15 → 30 → 60 → Kapalı
            val ch = Reminder.NAG_CHOICES
            if (!nag) { nag = true; nagMin = ch.first() }
            else { val i = ch.indexOf(nagMin); if (i < 0 || i == ch.lastIndex) nag = false else nagMin = ch[i + 1] }
            renderOn()
        }
        c.addView(nagBtn, lp())
        onBtn = pill("", Ui.CARD, Ui.TEXT, 13) { enabled = !enabled; renderOn() }
        c.addView(onBtn, lp().also { it.topMargin = dp(12) })
        c.addView(pill("Kaydet", Ui.ACCENT, Ui.ON_ACCENT, 15) { save() }, lp().also { it.topMargin = dp(8) })
        if (!isNew) {
            c.addView(pill("Sil", Ui.CARD, Ui.RED, 13) {
                AlertDialog.Builder(this)
                    .setMessage("\"${base.title}\" silinsin mi?")
                    .setPositiveButton("Sil") { _, _ -> Store.delete(this, base.id); finish() }
                    .setNegativeButton("Vazgeç", null)
                    .show()
            }, lp().also { it.topMargin = dp(8) })
        }
        setContentView(roundScroll(c))
        renderDays(); renderTimes(); renderDur(); renderOn()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("days", days)
        out.putIntArray("times", times.toIntArray())
        out.putInt("minutes", minutes)
        out.putBoolean("enabled", enabled)
        out.putBoolean("nag", nag)
        out.putInt("nagMin", nagMin)
    }

    private fun section(t: String) = label(t, 12, Ui.DIM, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(12), 0, dp(5))
        layoutParams = lp()
    }

    private fun step(t: String, onClick: () -> Unit) = pill(t, Ui.CARD2, Ui.TEXT, 17, 0, 0) { onClick() }.apply {
        layoutParams = lp(dp(40), dp(40))
    }

    private fun renderDays() {
        dayRows.removeAllViews()
        for (range in listOf(0..3, 4..6)) {
            val row = hbox().apply { gravity = Gravity.CENTER }
            for (i in range) {
                val on = days and (1 shl i) != 0
                row.addView(label(Reminder.DAY_LETTER[i], 12, if (on) Ui.ON_ACCENT else Ui.TEXT, bold = true).apply {
                    gravity = Gravity.CENTER
                    background = pressable(if (on) Ui.ACCENT else Ui.CARD, 100)
                    setOnClickListener { days = days xor (1 shl i); renderDays() }
                }, lp(dp(34), dp(34)).also { it.setMargins(dp(3), dp(2), dp(3), dp(2)) })
            }
            dayRows.addView(row, lp())
        }
    }

    private fun renderTimes() {
        times.sort()
        timeList.removeAllViews()
        for ((idx, m) in times.withIndex()) {
            val row = hbox().apply {
                background = pressable(Ui.CARD, 22)
                setPadding(dp(14), dp(2), dp(4), dp(2))
                setOnClickListener { pick(idx, m) }
            }
            row.addView(label(Reminder.hm(m), 18, Ui.TEXT, bold = true), lp(0, weight = 1f))
            row.addView(pill("✕", Ui.CARD, Ui.DIM, 13, 10, 6) { times.removeAt(idx); renderTimes() })
            timeList.addView(row, lp().also { it.bottomMargin = dp(5) })
        }
    }

    private fun renderDur() {
        durText.text = if (minutes == 0) "Yok" else Reminder.durText(minutes)
    }

    private fun renderOn() {
        onBtn.text = if (enabled) "● Açık" else "○ Kapalı"
        onBtn.setTextColor(if (enabled) Ui.ACCENT else Ui.DIM)
        nagBtn.text = if (nag) "↻ Her $nagMin dk" else "Kapalı"
        nagBtn.setTextColor(if (nag) Ui.ACCENT else Ui.DIM)
    }

    private fun pick(index: Int, initial: Int) {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, TimeActivity::class.java).putExtra("m", initial), 100 + index + 1)
    }

    @Deprecated("Activity sonucu")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val m = data.getIntExtra("m", -1).takeIf { it >= 0 } ?: return
        val index = requestCode - 101
        if (index in times.indices) times.removeAt(index)
        if (m !in times) times.add(m)
        renderTimes()
    }

    private fun save() {
        val t = title.text.toString().trim()
        val err = when {
            t.isEmpty() -> "Bir ad yaz"
            times.isEmpty() -> "Bir saat ekle"
            days == 0 -> "Bir gün seç"
            else -> null
        }
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
            return
        }
        Store.save(this, base.copy(title = t, days = days, times = times.toList(), minutes = minutes, enabled = enabled, nag = nag, nagMin = nagMin))
        finish()
    }
}
