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
    private val onChange: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensure(this)
        Alarms.rescheduleAll(this)

        val c = vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        c.addView(label("B12", 15, Ui.ACCENT, bold = true).apply { gravity = Gravity.CENTER }, lp())
        status = label("", 11, Ui.DIM).apply { gravity = Gravity.CENTER }
        c.addView(status, lp())
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
        Sync.peer(this, Sync.CAP_PHONE) { name, installed ->
            status.text = when {
                installed -> "Telefonla eşit"
                name != null -> "Telefonda B12 yok"
                else -> "Telefon uzakta · sonra eşitlenir"
            }
        }
    }

    override fun onStop() {
        Store.removeListener(onChange)
        super.onStop()
    }

    private fun render() {
        list.removeAllViews()
        val items = Store.visible(this)
        if (items.isEmpty()) {
            list.addView(label("Hatırlatma yok", 14, Ui.DIM).apply { gravity = Gravity.CENTER }, lp().also { it.topMargin = dp(10) })
            return
        }
        for (r in items) {
            val row = vbox().apply {
                background = pressable(Ui.CARD, 22)
                setPadding(dp(14), dp(8), dp(14), dp(9))
                setOnClickListener { startActivity(Intent(this@MainActivity, EditActivity::class.java).putExtra("id", r.id)) }
                setOnLongClickListener {
                    Store.save(this@MainActivity, r.copy(enabled = !r.enabled))
                    Toast.makeText(this@MainActivity, if (r.enabled) "Kapatıldı" else "Açıldı", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            row.addView(label("${r.icon()} ${r.title.ifBlank { "Adsız" }}", 15, if (r.enabled) Ui.TEXT else Ui.DIM, bold = true).apply {
                maxLines = 2
            })
            row.addView(label(r.nextText(), 12, if (r.enabled) Ui.ACCENT else Ui.DIM))
            list.addView(row, lp().also { it.topMargin = dp(6) })
        }
    }
}
