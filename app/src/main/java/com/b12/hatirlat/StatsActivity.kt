package com.b12.hatirlat

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Calendar

/** İstatistik ve rozetler. */
class StatsActivity : Activity() {
    private lateinit var c: LinearLayout
    private val onChange: () -> Unit = { render() }

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
    }

    override fun onStart() {
        super.onStart()
        Store.addListener(onChange)
        render()
    }

    override fun onStop() {
        Store.removeListener(onChange)
        super.onStop()
    }

    private fun section(t: String) = label(t, 14, Ui.DIM, bold = true).apply { setPadding(dp(4), dp(24), 0, dp(10)) }

    private fun card() = vbox().apply {
        background = rounded(Ui.CARD, 20)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun render() {
        c.removeAllViews()
        val top = hbox()
        top.addView(pill("←", Ui.CARD, Ui.TEXT, 18, 14, 6) { finish() })
        top.addView(label("İstatistik", 20, Ui.TEXT, bold = true).apply { setPadding(dp(14), 0, 0, 0) }, lp(0, weight = 1f))
        c.addView(top)

        val s = Progress.stats(this)
        // Özet kutuları
        val tiles = hbox().apply { setPadding(0, dp(18), 0, 0) }
        for ((big, small) in listOf(
            "Sv ${s.level}" to s.title,
            "🔥 ${s.streak}" to "en iyi ${s.best}",
            "${s.total}" to "toplam tik",
            "🧊 ${s.freezes}" to "koruyucu",
        )) {
            tiles.addView(vbox().apply {
                background = rounded(Ui.CARD, 16)
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(12), dp(4), dp(12))
                addView(label(big, 17, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER }, lp())
                addView(label(small, 11, Ui.DIM).apply { gravity = Gravity.CENTER; maxLines = 1 }, lp())
            }, lp(0, weight = 1f).also { it.setMargins(dp(3), 0, dp(3), 0) })
        }
        c.addView(tiles)
        c.addView(label("Her 7 günlük seride 1 seri koruyucu kazanırsın (en çok ${Progress.MAX_FREEZE}). Bir gün kaçırırsan otomatik kullanılır, serin bozulmaz.", 12, Ui.DIM).apply {
            setPadding(dp(4), dp(8), dp(4), 0)
        })

        // Isı haritası: son 16 hafta, sütun = hafta, satır = Pzt..Paz
        c.addView(section("Son 16 hafta"))
        val heat = card()
        val ticks = Progress.ticksByDay(this)
        val now = System.currentTimeMillis()
        val w0 = Progress.weekStart(now)
        val grid = hbox().apply { gravity = Gravity.TOP }
        val labels = vbox()
        for (d in 0..6) labels.addView(label(if (d % 2 == 0) Reminder.DAY_LETTER[d] else "", 9, Ui.DIM).apply { gravity = Gravity.CENTER_VERTICAL }, lp(dp(18), dp(15)).also { it.bottomMargin = dp(3) })
        grid.addView(labels)
        val today = Progress.dayKey(now)
        for (w in 15 downTo 0) {
            val col = vbox()
            for (d in 0..6) {
                val day = Calendar.getInstance().apply { timeInMillis = w0; add(Calendar.DAY_OF_YEAR, -7 * w + d) }.timeInMillis
                val k = Progress.dayKey(day)
                val n = ticks[k] ?: 0
                val color = when {
                    k > today -> 0x00000000
                    n == 0 -> Ui.CARD2
                    n <= 2 -> 0x558AB4FF
                    n <= 4 -> 0x998AB4FF.toInt()
                    else -> Ui.ACCENT
                }
                col.addView(View(this).apply { background = rounded(color, 3, if (k == today) Ui.TEXT else 0) }, lp(dp(15), dp(15)).also { it.bottomMargin = dp(3) })
            }
            grid.addView(col, lp(0, weight = 1f).also { it.setMargins(dp(1), 0, dp(1), 0) })
        }
        heat.addView(grid)
        val legend = hbox().apply { setPadding(0, dp(8), 0, 0) }
        legend.addView(label("az", 11, Ui.DIM))
        for (col in listOf(Ui.CARD2, 0x558AB4FF, 0x998AB4FF.toInt(), Ui.ACCENT)) legend.addView(View(this).apply { background = rounded(col, 3) }, lp(dp(12), dp(12)).also { it.setMargins(dp(4), 0, 0, 0) })
        legend.addView(label("  çok", 11, Ui.DIM))
        heat.addView(legend)
        c.addView(heat)

        // Haftalık XP
        c.addView(section("Haftalık XP"))
        val xpCard = card()
        val xs = Progress.weeklyXp(this, 8, now)
        val maxXp = (xs.maxOrNull() ?: 0).coerceAtLeast(1)
        val bars = hbox().apply { gravity = Gravity.BOTTOM }
        for ((i, x) in xs.withIndex()) {
            val col = vbox().apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
            col.addView(label(if (x > 0) "$x" else "", 10, Ui.DIM).apply { gravity = Gravity.CENTER }, lp())
            col.addView(View(this).apply { background = rounded(if (i == xs.lastIndex) Ui.ACCENT else 0x998AB4FF.toInt(), 6) },
                lp(LinearLayout.LayoutParams.MATCH_PARENT, dp(4 + 96 * x / maxXp)))
            col.addView(label(if (i == xs.lastIndex) "bu" else "-${xs.lastIndex - i}", 10, Ui.DIM).apply { gravity = Gravity.CENTER }, lp())
            bars.addView(col, lp(0, dp(140), 1f).also { it.setMargins(dp(4), 0, dp(4), 0) })
        }
        xpCard.addView(bars)
        c.addView(xpCard)

        // Alışkanlıklar
        val habits = Store.visible(this)
        if (habits.isNotEmpty()) {
            c.addView(section("Alışkanlıklar · son 30 gün"))
            val hc = card()
            for (r in habits) {
                val pct = Progress.completion(this, r)
                val streak = Progress.habitStreak(this, r)
                val row = vbox().apply { setPadding(0, dp(6), 0, dp(8)) }
                val line = hbox()
                line.addView(label("${r.icon()}  ${r.title}", 15, Ui.TEXT, bold = true), lp(0, weight = 1f))
                line.addView(label(listOfNotNull(if (streak > 0) "🔥 $streak" else null, pct?.let { "%$it" } ?: "—").joinToString("   "), 13, Ui.DIM, bold = true))
                row.addView(line)
                row.addView(bar((pct ?: 0) / 100f, 6), lp().also { it.topMargin = dp(6) })
                hc.addView(row)
            }
            c.addView(hc)
        }

        // Rozetler
        val badges = Progress.badges(this)
        c.addView(section("Rozetler · ${badges.count { it.unlocked }}/${badges.size}"))
        for (chunk in badges.chunked(3)) {
            val row = hbox().apply { gravity = Gravity.TOP }
            for (b in chunk) {
                row.addView(vbox().apply {
                    background = rounded(if (b.unlocked) Ui.CARD2 else Ui.CARD, 16, if (b.unlocked) Ui.ACCENT else 0)
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(6), dp(12), dp(6), dp(12))
                    alpha = if (b.unlocked) 1f else 0.55f
                    addView(label(if (b.unlocked) b.icon else "🔒", 26).apply { gravity = Gravity.CENTER }, lp())
                    addView(label(b.name, 13, Ui.TEXT, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }, lp())
                    addView(label(b.desc, 10, Ui.DIM).apply { gravity = Gravity.CENTER }, lp())
                    if (!b.unlocked && b.goal > 1) {
                        addView(bar(b.progress.toFloat() / b.goal, 4), lp().also { it.setMargins(dp(8), dp(6), dp(8), 0) })
                        addView(label("${b.progress.coerceAtMost(b.goal)}/${b.goal}", 10, Ui.DIM).apply { gravity = Gravity.CENTER }, lp())
                    }
                }, lp(0, weight = 1f).also { it.setMargins(dp(4), dp(4), dp(4), dp(4)) })
            }
            repeat(3 - chunk.size) { row.addView(View(this), lp(0, weight = 1f).also { it.setMargins(dp(4), 0, dp(4), 0) }) }
            c.addView(row)
        }
    }
}
