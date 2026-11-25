package com.b12.hatirlat

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var banners: LinearLayout
    private lateinit var status: TextView
    private lateinit var greet: TextView
    private lateinit var date: TextView
    private lateinit var hero: LinearLayout
    private val onChange: () -> Unit = { render() }
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { renderHeader(); ticker.postDelayed(this, 30_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensure(this)
        Alarms.rescheduleAll(this)

        val content = vbox().apply { setPadding(dp(20), dp(18), dp(20), dp(110)) }
        greet = label("", 28, Ui.TEXT, bold = true)
        date = label("", 15, Ui.DIM)
        content.addView(greet)
        content.addView(date)
        hero = vbox()
        content.addView(hero, lp().also { it.topMargin = dp(18) })
        status = label("⌚ Saat aranıyor…", 13, Ui.DIM)
        status.setPadding(dp(12), dp(8), dp(12), dp(8))
        status.background = rounded(Ui.CARD, 12)
        content.addView(status, lp().also { it.topMargin = dp(14) })
        banners = vbox()
        content.addView(banners)
        list = vbox()
        content.addView(list, lp().also { it.topMargin = dp(10) })

        val scroll = ScrollView(this).apply { addView(content); isFillViewport = true }
        val fab = label("+", 30, Ui.ON_ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            background = pressable(Ui.ACCENT, 20)
            elevation = dp(6).toFloat()
            contentDescription = "Yeni hatırlatma"
            setOnClickListener { startActivity(Intent(this@MainActivity, EditActivity::class.java)) }
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Ui.BG)
            addView(scroll)
            addView(fab, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END).also {
                it.setMargins(0, 0, dp(22), dp(26))
            })
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onStart() {
        super.onStart()
        Store.addListener(onChange)
        render()
        ticker.postDelayed(tick, 30_000)
        Sync.pullAll(this)
        Sync.peer(this, Sync.CAP_WATCH) { name, installed ->
            status.text = when {
                installed -> "⌚ ${name ?: "Saat"} bağlı · eşitleniyor"
                name != null -> "⌚ $name bağlı ama saatte B12 yüklü değil"
                else -> "⌚ Saat şu an bağlı değil · bağlanınca eşitlenir"
            }
        }
    }

    override fun onStop() {
        Store.removeListener(onChange)
        ticker.removeCallbacks(tick)
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        render()
    }

    private fun render() {
        renderBanners()
        renderHeader()
        list.removeAllViews()
        val items = Store.visible(this)
        if (items.isEmpty()) {
            list.addView(label("Henüz hatırlatma yok", 18, Ui.TEXT, bold = true), lp().also { it.topMargin = dp(18) })
            list.addView(label("Sağ alttaki + ile ekle ya da hızlı başlamak için birine dokun:", 14, Ui.DIM), lp().also { it.topMargin = dp(4) })
            for (p in Reminder.presets()) {
                val sub = listOf(p.timesText(), p.daysText(), p.durationText()).filter { it.isNotEmpty() }.joinToString(" · ")
                val row = vbox().apply {
                    background = pressable(Ui.CARD, 16)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    addView(label("+  ${p.title}", 16, Ui.TEXT, bold = true))
                    addView(label(sub, 13, Ui.DIM))
                    setOnClickListener { Store.save(this@MainActivity, p) }
                }
                list.addView(row, lp().also { it.topMargin = dp(10) })
            }
            return
        }
        list.addView(label("Tümü · ${items.size}", 14, Ui.DIM, bold = true).apply { setPadding(dp(4), dp(18), 0, 0) })
        for (r in items) list.addView(card(r), lp().also { it.topMargin = dp(10) })
    }

    private fun renderHeader() {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        greet.text = when (h) {
            in 5..11 -> "Günaydın ☀️"
            in 12..17 -> "İyi günler 👋"
            in 18..22 -> "İyi akşamlar 🌆"
            else -> "İyi geceler 🌙"
        }
        date.text = SimpleDateFormat("d MMMM EEEE", Locale("tr")).format(c.time)
        hero.removeAllViews()
        hero.background = null
        hero.setPadding(0, 0, 0, 0)
        val now = System.currentTimeMillis()
        val next = Store.visible(this).filter { it.enabled }
            .mapNotNull { r -> r.nextAfter(now)?.let { r to it } }.minByOrNull { it.second } ?: return
        val (r, at) = next
        hero.background = rounded(Ui.ACCENT, 24)
        hero.setPadding(dp(20), dp(16), dp(20), dp(18))
        hero.addView(label("SIRADAKİ · ${Reminder.untilText(at, now)}", 12, 0xB30F1216.toInt(), bold = true))
        hero.addView(label("${r.icon()}  ${r.title}", 22, Ui.ON_ACCENT, bold = true).apply { setPadding(0, dp(6), 0, 0) })
        hero.addView(label(r.nextText(now) + if (r.minutes > 0) " · ${r.durationText()}" else "", 15, 0xCC0F1216.toInt()))
        hero.setOnClickListener { startActivity(Intent(this, EditActivity::class.java).putExtra("id", r.id)) }
    }

    private fun card(r: Reminder): View {
        val c = hbox().apply {
            background = pressable(Ui.CARD, 18)
            setPadding(dp(14), dp(14), dp(10), dp(14))
            setOnClickListener { startActivity(Intent(this@MainActivity, EditActivity::class.java).putExtra("id", r.id)) }
            setOnLongClickListener { menu(r); true }
        }
        c.addView(label(r.icon(), 22).apply {
            gravity = Gravity.CENTER
            background = rounded(Ui.CARD2, 100)
            alpha = if (r.enabled) 1f else 0.45f
        }, lp(dp(46), dp(46)).also { it.rightMargin = dp(14) })
        val texts = vbox()
        val fg = if (r.enabled) Ui.TEXT else Ui.DIM
        texts.addView(label(r.title.ifBlank { "Adsız" }, 17, fg, bold = true))
        texts.addView(label(r.timesText(), 15, if (r.enabled) Ui.ACCENT else Ui.DIM).apply { setPadding(0, dp(3), 0, 0) })
        val meta = listOf(r.daysText(), r.durationText()).filter { it.isNotEmpty() }.joinToString(" · ")
        texts.addView(label(meta, 13, Ui.DIM).apply { setPadding(0, dp(2), 0, 0) })
        c.addView(texts, lp(0, weight = 1f))
        c.addView(Switch(this).apply {
            isChecked = r.enabled
            styleSwitch(this)
            setOnCheckedChangeListener { _, on -> Store.save(this@MainActivity, r.copy(enabled = on)) }
        })
        return c
    }

    private fun menu(r: Reminder) {
        AlertDialog.Builder(this)
            .setTitle(r.title)
            .setItems(arrayOf(if (r.enabled) "Kapat" else "Aç", "Düzenle", "Sil")) { _, which ->
                when (which) {
                    0 -> Store.save(this, r.copy(enabled = !r.enabled))
                    1 -> startActivity(Intent(this, EditActivity::class.java).putExtra("id", r.id))
                    2 -> AlertDialog.Builder(this)
                        .setMessage("\"${r.title}\" telefondan ve saatten silinsin mi?")
                        .setPositiveButton("Sil") { _, _ -> Store.delete(this, r.id) }
                        .setNegativeButton("Vazgeç", null)
                        .show()
                }
            }
            .show()
    }

    private fun renderBanners() {
        banners.removeAllViews()
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            banner("Bildirimler kapalı, hatırlatmalar görünmez.", "Aç") {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                    shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                } else {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }
            }
        }
        if (!Alarms.canExact(this) && Build.VERSION.SDK_INT >= 31) {
            banner("Tam zamanlı alarm izni yok, hatırlatmalar gecikebilir.", "İzin ver") {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        val pm = getSystemService(PowerManager::class.java)
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (!pm.isIgnoringBatteryOptimizations(packageName) && !prefs.getBoolean("hideBattery", false)) {
            banner("Samsung pil tasarrufu uygulamayı uyutup hatırlatmaları geciktirebilir.", "Kısıtlamayı kaldır",
                onClose = { prefs.edit().putBoolean("hideBattery", true).apply(); renderBanners() }) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderBanners()
    }

    private fun banner(text: String, button: String, onClose: (() -> Unit)? = null, onClick: () -> Unit) {
        val b = hbox().apply {
            background = rounded(Ui.WARN, 14)
            setPadding(dp(14), dp(10), dp(10), dp(10))
            addView(label(text, 13, Ui.TEXT), lp(0, weight = 1f))
            addView(pill(button, Ui.ACCENT, Ui.ON_ACCENT, 13, 12, 7) { onClick() }, lp(LinearLayout.LayoutParams.WRAP_CONTENT).also { it.leftMargin = dp(10) })
            if (onClose != null) addView(pill("✕", Ui.WARN, Ui.DIM, 14, 10, 7) { onClose() })
        }
        banners.addView(b, lp().also { it.topMargin = dp(10) })
    }
}

fun styleSwitch(s: Switch) {
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    s.thumbTintList = ColorStateList(states, intArrayOf(Ui.ACCENT, 0xFFB8C0CC.toInt()))
    s.trackTintList = ColorStateList(states, intArrayOf(0x808AB4FF.toInt(), 0x40FFFFFF))
}
