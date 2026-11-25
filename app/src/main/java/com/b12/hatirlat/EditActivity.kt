package com.b12.hatirlat

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class EditActivity : Activity() {
    private lateinit var base: Reminder
    private var isNew = true
    private var days = Reminder.ALL_DAYS
    private val times = mutableListOf<Int>()
    private var minutes = 0
    private var enabled = true
    private var spreadCount = 4
    private var spreadStart = 9 * 60
    private var spreadEnd = 21 * 60

    private lateinit var title: EditText
    private lateinit var dayRow: LinearLayout
    private lateinit var timeList: LinearLayout
    private lateinit var durText: TextView
    private lateinit var spreadText: TextView
    private lateinit var spreadRange: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val existing = intent.getStringExtra("id")?.let { Store.get(this, it) }
        isNew = existing == null
        base = existing ?: Reminder()
        days = base.days
        times.addAll(base.times.sorted())
        minutes = base.minutes
        enabled = base.enabled
        savedInstanceState?.let { st ->
            days = st.getInt("days", days)
            times.clear(); times.addAll(st.getIntArray("times")?.toList() ?: base.times)
            minutes = st.getInt("minutes", minutes)
            enabled = st.getBoolean("enabled", enabled)
        }

        val c = vbox().apply { setPadding(dp(20), dp(14), dp(20), dp(40)) }

        // Üst çubuk
        val top = hbox()
        top.addView(pill("←", Ui.CARD, Ui.TEXT, 18, 14, 6) { finish() })
        top.addView(label(if (isNew) "Yeni hatırlatma" else "Düzenle", 20, Ui.TEXT, bold = true).apply {
            setPadding(dp(14), 0, 0, 0)
        }, lp(0, weight = 1f))
        top.addView(pill("Kaydet", Ui.ACCENT, Ui.ON_ACCENT, 15) { save() })
        c.addView(top)

        // Ne?
        c.addView(section("Ne hatırlatayım?"))
        title = EditText(this).apply {
            setText(base.title)
            hint = "ör. Diş fırçala"
            setHintTextColor(Ui.DIM)
            setTextColor(Ui.TEXT)
            textSize = 18f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = rounded(Ui.CARD, 14)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        c.addView(title)
        if (isNew) {
            val chips = hbox()
            for (p in Reminder.presets()) {
                chips.addView(pill(p.title, Ui.CARD, Ui.DIM, 13, 12, 7) { applyPreset(p) }, lp(LinearLayout.LayoutParams.WRAP_CONTENT).also { it.rightMargin = dp(8) })
            }
            c.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            }, lp().also { it.topMargin = dp(10) })
        }

        // Günler
        c.addView(section("Hangi günler?"))
        dayRow = hbox()
        c.addView(dayRow)
        val quick = hbox()
        for ((t, mask) in listOf("Her gün" to Reminder.ALL_DAYS, "Hafta içi" to Reminder.WEEKDAYS, "Hafta sonu" to Reminder.WEEKEND)) {
            quick.addView(pill(t, Ui.CARD, Ui.TEXT, 13, 12, 7) { days = mask; renderDays() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT).also { it.rightMargin = dp(8) })
        }
        c.addView(quick, lp().also { it.topMargin = dp(10) })

        // Saatler
        c.addView(section("Saat kaçta? (günde kaç kez)"))
        timeList = vbox()
        c.addView(timeList)
        c.addView(pill("+ Saat ekle", Ui.CARD, Ui.ACCENT, 15) {
            pickTime(times.maxOrNull()?.let { (it + 60) % 1440 } ?: (9 * 60)) { m -> if (m !in times) times.add(m); renderTimes() }
        }, lp().also { it.topMargin = dp(8) })

        // Günde N kez
        val spread = vbox().apply {
            background = rounded(Ui.CARD, 16)
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }
        spread.addView(label("Eşit aralıklı oluştur", 14, Ui.DIM))
        val countRow = hbox()
        countRow.addView(label("Günde", 16), lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        countRow.addView(stepBtn("−") { spreadCount = (spreadCount - 1).coerceAtLeast(2); renderSpread() })
        spreadText = label("", 18, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER; minWidth = dp(36) }
        countRow.addView(spreadText)
        countRow.addView(stepBtn("+") { spreadCount = (spreadCount + 1).coerceAtMost(24); renderSpread() })
        countRow.addView(label("kez", 16), lp(LinearLayout.LayoutParams.WRAP_CONTENT))
        spread.addView(countRow, lp().also { it.topMargin = dp(6) })
        spreadRange = hbox()
        spread.addView(spreadRange, lp().also { it.topMargin = dp(8) })
        spread.addView(pill("Saatleri oluştur", Ui.CARD2, Ui.ACCENT, 14) {
            times.clear()
            times.addAll(Reminder.spread(spreadCount, spreadStart, spreadEnd))
            renderTimes()
        }, lp().also { it.topMargin = dp(10) })
        c.addView(spread, lp().also { it.topMargin = dp(12) })

        // Süre
        c.addView(section("Süre (isteğe bağlı)"))
        val durRow = hbox().apply {
            background = rounded(Ui.CARD, 16)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        durRow.addView(stepBtn("−") { minutes = (minutes - 15).coerceAtLeast(0); renderDur() })
        durText = label("", 17, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER }
        durRow.addView(durText, lp(0, weight = 1f))
        durRow.addView(stepBtn("+") { minutes = (minutes + 15).coerceAtMost(240); renderDur() })
        c.addView(durRow)
        c.addView(label("Süre verirsen bildirimde \"Başla\" çıkar; süre bitince telefon ve saat haber verir (ör. 1 saat kitap oku).", 13, Ui.DIM).apply {
            setPadding(dp(4), dp(6), dp(4), 0)
        })

        // Açık / kapalı
        val onRow = hbox().apply {
            background = rounded(Ui.CARD, 16)
            setPadding(dp(16), dp(10), dp(12), dp(10))
        }
        onRow.addView(label("Hatırlatma açık", 16), lp(0, weight = 1f))
        onRow.addView(Switch(this).apply {
            isChecked = enabled
            styleSwitch(this)
            setOnCheckedChangeListener { _, on -> enabled = on }
        })
        c.addView(onRow, lp().also { it.topMargin = dp(22) })

        if (!isNew) {
            c.addView(pill("Sil", Ui.CARD, Ui.RED, 15) {
                AlertDialog.Builder(this)
                    .setTitle("Silinsin mi?")
                    .setMessage("\"${base.title}\" telefondan ve saatten silinecek.")
                    .setPositiveButton("Sil") { _, _ -> Store.delete(this, base.id); finish() }
                    .setNegativeButton("Vazgeç", null)
                    .show()
            }, lp().also { it.topMargin = dp(14) })
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(c)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(scroll)
        renderDays(); renderTimes(); renderDur(); renderSpread()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("days", days)
        out.putIntArray("times", times.toIntArray())
        out.putInt("minutes", minutes)
        out.putBoolean("enabled", enabled)
    }

    private fun section(t: String) = label(t, 14, Ui.DIM, bold = true).apply { setPadding(dp(4), dp(22), 0, dp(8)) }

    private fun stepBtn(t: String, onClick: () -> Unit) =
        pill(t, Ui.CARD2, Ui.TEXT, 20, 0, 0) { onClick() }.apply {
            layoutParams = lp(dp(44), dp(44)).also { it.setMargins(dp(6), 0, dp(6), 0) }
        }

    private fun applyPreset(p: Reminder) {
        title.setText(p.title)
        title.setSelection(p.title.length)
        days = p.days
        times.clear(); times.addAll(p.times)
        minutes = p.minutes
        renderDays(); renderTimes(); renderDur()
    }

    private fun renderDays() {
        dayRow.removeAllViews()
        for (i in 0..6) {
            val on = days and (1 shl i) != 0
            val v = label(Reminder.DAY_LETTER[i], 14, if (on) Ui.ON_ACCENT else Ui.TEXT, bold = true).apply {
                gravity = Gravity.CENTER
                background = pressable(if (on) Ui.ACCENT else Ui.CARD, 100)
                setOnClickListener { days = days xor (1 shl i); renderDays() }
            }
            dayRow.addView(v, lp(0, dp(44), 1f).also { it.setMargins(dp(3), 0, dp(3), 0) })
        }
    }

    private fun renderTimes() {
        times.sort()
        timeList.removeAllViews()
        if (times.isEmpty()) {
            timeList.addView(label("Saat yok, en az bir saat ekle.", 14, Ui.RED).apply { setPadding(dp(4), 0, 0, dp(4)) })
        }
        for ((idx, m) in times.withIndex()) {
            val row = hbox().apply {
                background = pressable(Ui.CARD, 16)
                setPadding(dp(16), dp(6), dp(6), dp(6))
                setOnClickListener { pickTime(m) { n -> times.removeAt(idx); if (n !in times) times.add(n); renderTimes() } }
            }
            row.addView(label(Reminder.hm(m), 24, Ui.TEXT, bold = true), lp(0, weight = 1f))
            row.addView(pill("✕", Ui.CARD, Ui.DIM, 16, 14, 8) { times.removeAt(idx); renderTimes() })
            timeList.addView(row, lp().also { it.bottomMargin = dp(8) })
        }
    }

    private fun renderDur() {
        durText.text = if (minutes == 0) "Süre yok" else Reminder.durText(minutes)
    }

    private fun renderSpread() {
        spreadText.text = spreadCount.toString()
        spreadRange.removeAllViews()
        spreadRange.addView(pill(Reminder.hm(spreadStart), Ui.CARD2, Ui.TEXT, 15) {
            pickTime(spreadStart) { spreadStart = it; renderSpread() }
        })
        spreadRange.addView(label("  ile  ", 15, Ui.DIM))
        spreadRange.addView(pill(Reminder.hm(spreadEnd), Ui.CARD2, Ui.TEXT, 15) {
            pickTime(spreadEnd) { spreadEnd = it; renderSpread() }
        })
        spreadRange.addView(label("  arası", 15, Ui.DIM))
    }

    private fun pickTime(initial: Int, done: (Int) -> Unit) {
        TimePickerDialog(this, { _, h, m -> done(h * 60 + m) }, initial / 60, initial % 60, true).show()
    }

    private fun save() {
        val t = title.text.toString().trim()
        when {
            t.isEmpty() -> return toast("Bir ad yaz")
            times.isEmpty() -> return toast("En az bir saat ekle")
            days == 0 -> return toast("En az bir gün seç")
        }
        Store.save(this, base.copy(title = t, days = days, times = times.toList(), minutes = minutes, enabled = enabled))
        finish()
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
