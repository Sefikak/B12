package com.b12.hatirlat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : WearActivity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var level: LinearLayout
    private lateinit var upd: TextView
    private val onChange: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensure(this)
        Alarms.rescheduleAll(this)

        val c = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        c.addView(label("B12", 15, Ui.ACCENT, bold = true).apply { gravity = Gravity.CENTER }, lp())
        status = label("", 11, Ui.DIM).apply { gravity = Gravity.CENTER }
        c.addView(status, lp())
        upd = pill("", Ui.ACCENT, Ui.ON_ACCENT, 12, 10, 6) {}.apply { visibility = android.view.View.GONE }
        c.addView(upd, lp().also { it.topMargin = dp(6) })
        level = vbox()
        c.addView(level, lp().also { it.topMargin = dp(6) })
        list = vbox()
        c.addView(list, lp().also { it.topMargin = dp(6) })
        c.addView(pill("+ Yeni", Ui.ACCENT, Ui.ON_ACCENT, 14) {
            startActivity(Intent(this, EditActivity::class.java))
        }, lp().also { it.topMargin = dp(10) })
        setContentView(roundScroll(c))

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        Store.addListener(onChange)
        render()
        Sync.pullAll(this)
        Updater.check(this) { r, _ -> if (!isFinishing) showUpdate(r) }
        Sync.peer(this, Sync.CAP_PHONE) { name, installed ->
            status.text = when {
                installed -> "Telefonla eşit"
                name != null -> "Telefonda B12 yok"
                else -> "Telefon uzakta · sonra eşitlenir"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Updater.resume(this)
    }

    /** Yeni sürüm varsa üstte düğme: dokununca indirir ve yükleme ekranını açar. */
    private fun showUpdate(r: Updater.Release?) {
        if (r == null) { upd.visibility = android.view.View.GONE; return }
        upd.visibility = android.view.View.VISIBLE
        upd.text = "⬆ ${r.version} güncellemesi"
        upd.setOnClickListener { Updater.start(this, r) { upd.text = it } }
    }

    override fun onStop() {
        Store.removeListener(onChange)
        super.onStop()
    }

    private fun render() {
        val s = Progress.stats(this)
        level.removeAllViews()
        level.addView(label("Sv ${s.level} · ${s.title}" + (if (s.streak > 0) "  🔥${s.streak}" else "") + if (s.freezes > 0) " 🧊${s.freezes}" else "", 13, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER }, lp())
        level.addView(bar(s.into.toFloat() / s.need, 5), lp().also { it.setMargins(dp(14), dp(4), dp(14), 0) })
        level.addView(label("${s.into}/${s.need} XP", 10, Ui.DIM).apply { gravity = Gravity.CENTER }, lp())
        list.removeAllViews()
        val items = Store.visible(this)
        if (items.isEmpty()) {
            list.addView(label("Hatırlatma yok", 14, Ui.DIM).apply { gravity = Gravity.CENTER }, lp().also { it.topMargin = dp(10) })
            return
        }
        for (r in items) {
            val row = hbox().apply {
                background = pressable(Ui.CARD, 22)
                setPadding(dp(14), dp(8), dp(8), dp(9))
                setOnClickListener { startActivity(Intent(this@MainActivity, EditActivity::class.java).putExtra("id", r.id)) }
                setOnLongClickListener {
                    Store.save(this@MainActivity, r.copy(enabled = !r.enabled))
                    Toast.makeText(this@MainActivity, if (r.enabled) "Kapatıldı" else "Açıldı", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            val texts = vbox()
            texts.addView(label("${r.icon()} ${r.title.ifBlank { "Adsız" }}", 15, if (r.enabled) Ui.TEXT else Ui.DIM, bold = true).apply {
                maxLines = 2
            })
            val sub = listOfNotNull(
                r.nextText(),
                if (r.weekly > 0) "hafta ${Progress.weekTicks(this, r).coerceAtMost(r.weekly)}/${r.weekly}" else null,
                Progress.habitStreak(this, r).takeIf { it >= 2 }?.let { "🔥$it" },
            ).joinToString(" · ")
            texts.addView(label(sub, 12, if (r.enabled) Ui.ACCENT else Ui.DIM))
            row.addView(texts, lp(0, weight = 1f))
            val (done, total) = Progress.today(this, r)
            if (total > 0) row.addView(checkButton(done, total, 38) {
                if (!Progress.markNow(this, r)) Toast.makeText(this, "Bugün tamam ✓", Toast.LENGTH_SHORT).show()
            })
            list.addView(row, lp().also { it.topMargin = dp(6) })
        }
    }
}
