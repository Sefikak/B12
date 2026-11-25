package com.b12.hatirlat

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sessiz saatler ve yedekleme. */
class SettingsActivity : Activity() {
    private lateinit var c: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        c = vbox().apply { setPadding(dp(20), dp(14), dp(20), dp(40)) }
        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(c) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(scroll)
        render()
    }

    private fun section(t: String) = label(t, 14, Ui.DIM, bold = true).apply { setPadding(dp(4), dp(24), 0, dp(10)) }

    private fun card() = vbox().apply {
        background = rounded(Ui.CARD, 18)
        setPadding(dp(16), dp(12), dp(16), dp(14))
    }

    private fun render() {
        c.removeAllViews()
        val top = hbox()
        top.addView(pill("←", Ui.CARD, Ui.TEXT, 18, 14, 6) { finish() })
        top.addView(label("Ayarlar", 20, Ui.TEXT, bold = true).apply { setPadding(dp(14), 0, 0, 0) }, lp(0, weight = 1f))
        c.addView(top)

        // Sessiz saatler
        val s = Settings.get(this)
        c.addView(section("Sessiz saatler"))
        val q = card()
        val row = hbox()
        row.addView(label("Gece rahatsız etme", 16), lp(0, weight = 1f))
        row.addView(Switch(this).apply {
            isChecked = s.quietOn
            styleSwitch(this)
            setOnCheckedChangeListener { _, on -> Settings.save(this@SettingsActivity, Settings.get(this@SettingsActivity).copy(quietOn = on)); render() }
        })
        q.addView(row)
        if (s.quietOn) {
            val times = hbox().apply { setPadding(0, dp(10), 0, 0) }
            times.addView(pill(Reminder.hm(s.quietStart), Ui.CARD2, Ui.TEXT, 15) {
                pick(s.quietStart) { Settings.save(this, Settings.get(this).copy(quietStart = it)); render() }
            })
            times.addView(label("  ile  ", 15, Ui.DIM))
            times.addView(pill(Reminder.hm(s.quietEnd), Ui.CARD2, Ui.TEXT, 15) {
                pick(s.quietEnd) { Settings.save(this, Settings.get(this).copy(quietEnd = it)); render() }
            })
            times.addView(label("  arası", 15, Ui.DIM))
            q.addView(times)
        }
        q.addView(label("Bu saatlerde bildirimler sessiz gelir, \"yapana kadar\" ısrarı ve rozet sesleri susar. Saatle de eşitlenir.", 13, Ui.DIM).apply {
            setPadding(0, dp(10), 0, 0)
        })
        c.addView(q)

        // Yedek
        c.addView(section("Yedekleme"))
        val bk = card()
        bk.addView(label("Hatırlatmalar, XP geçmişi ve ayarlar tek bir dosyaya kaydedilir. Kaydederken Google Drive'ı seçersen buluta, bilgisayara bağlıyken telefon klasörünü seçersen oradan bilgisayara alabilirsin.", 13, Ui.DIM))
        bk.addView(pill("Yedeği dışa aktar", Ui.ACCENT, Ui.ON_ACCENT, 15) { exportBackup() }, lp().also { it.topMargin = dp(12) })
        bk.addView(pill("Yedekten geri yükle", Ui.CARD2, Ui.TEXT, 15) { importBackup() }, lp().also { it.topMargin = dp(8) })
        c.addView(bk)

        // Widget ipucu
        c.addView(section("Ana ekran"))
        val w = card()
        w.addView(label("Ana ekranda boş bir yere uzun bas → Widget'lar → B12. Bugünün işleri ✓ düğmeleriyle görünür.", 13, Ui.DIM))
        c.addView(w)
    }

    private fun pick(initial: Int, done: (Int) -> Unit) {
        TimePickerDialog(this, { _, h, m -> done(h * 60 + m) }, initial / 60, initial % 60, true).show()
    }

    // ---- yedek
    private fun exportBackup() {
        val name = "B12-yedek-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, name), 1)
    }

    private fun importBackup() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*"), 2)
    }

    @Deprecated("Activity sonucu")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            if (requestCode == 1) {
                val o = JSONObject()
                    .put("app", "B12").put("v", 1).put("at", System.currentTimeMillis())
                    .put("reminders", JSONArray(Store.all(this).map { it.toJson() }))
                    .put("done", JSONArray(Progress.events(this).map { it.toJson() }))
                    .put("settings", Settings.get(this).toJson())
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(o.toString(1).toByteArray()) }
                Toast.makeText(this, "Yedek kaydedildi ✓", Toast.LENGTH_SHORT).show()
            } else if (requestCode == 2) {
                val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                val o = JSONObject(text)
                require(o.optString("app") == "B12") { "Bu bir B12 yedeği değil" }
                val rs = o.optJSONArray("reminders") ?: JSONArray()
                val ds = o.optJSONArray("done") ?: JSONArray()
                AlertDialog.Builder(this)
                    .setTitle("Geri yüklensin mi?")
                    .setMessage("${(0 until rs.length()).count { !rs.getJSONObject(it).optBoolean("deleted") }} hatırlatma ve ${ds.length()} tik kaydı mevcut verilerle birleştirilecek (hiçbir şey silinmez).")
                    .setPositiveButton("Geri yükle") { _, _ ->
                        var n = 0
                        for (i in 0 until rs.length()) if (Store.merge(this, Reminder.fromJson(rs.getJSONObject(i)))) n++
                        for (i in 0 until ds.length()) Progress.merge(this, Done.fromJson(ds.getJSONObject(i)))
                        o.optJSONObject("settings")?.let { Settings.merge(this, Settings.fromJson(it)) }
                        Sync.pullAll(this)   // yeni gelenleri saate de gönder
                        Toast.makeText(this, "Geri yüklendi: $n hatırlatma ✓", Toast.LENGTH_LONG).show()
                        render()
                    }
                    .setNegativeButton("Vazgeç", null)
                    .show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Olmadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
