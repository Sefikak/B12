package com.b12.hatirlat

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * Bir kalıcı hatırlatma. Gün bitleri: 0 = Pazartesi … 6 = Pazar. Saatler gün içindeki dakika (0..1439).
 * updatedAt eşitlemede kimin kazanacağını belirler (yenisi kazanır); silinenler "deleted" olarak kalır ki
 * öbür cihazda geri dirilmesin.
 */
data class Reminder(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val days: Int = ALL_DAYS,
    val times: List<Int> = listOf(9 * 60),
    val minutes: Int = 0,
    val enabled: Boolean = true,
    val updatedAt: Long = 0L,
    val deleted: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("days", days)
        .put("times", JSONArray(times)).put("minutes", minutes)
        .put("enabled", enabled).put("updatedAt", updatedAt).put("deleted", deleted)

    fun hasDay(i: Int) = days and (1 shl i) != 0

    /** [from] anından sonraki ilk çalma anı; gün ya da saat yoksa null. */
    fun nextAfter(from: Long): Long? {
        if (days and ALL_DAYS == 0 || times.isEmpty()) return null
        val sorted = times.sorted()
        val c = Calendar.getInstance()
        for (d in 0..7) {
            c.timeInMillis = from
            c.add(Calendar.DAY_OF_YEAR, d)
            if (!hasDay(dayIndex(c))) continue
            for (t in sorted) {
                c.set(Calendar.HOUR_OF_DAY, t / 60)
                c.set(Calendar.MINUTE, t % 60)
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
                if (c.timeInMillis > from) return c.timeInMillis
            }
        }
        return null
    }

    fun daysText(): String = when (days and ALL_DAYS) {
        ALL_DAYS -> "Her gün"
        WEEKDAYS -> "Hafta içi"
        WEEKEND -> "Hafta sonu"
        0 -> "Gün seçilmedi"
        else -> (0..6).filter { hasDay(it) }.joinToString(", ") { DAY_SHORT[it] }
    }

    fun timesText(sep: String = " · ") = times.sorted().joinToString(sep) { hm(it) }

    fun durationText() = durText(minutes)

    /** Başlıktan tahmin edilen simge. */
    fun icon(): String {
        val t = title.lowercase()
        return ICONS.firstOrNull { (keys, _) -> keys.any { it in t } }?.second ?: "🔔"
    }

    /** "Bugün 21:00", "Yarın 08:00", "Cum 08:00" */
    fun nextText(now: Long = System.currentTimeMillis()): String {
        if (!enabled) return "Kapalı"
        val at = nextAfter(now) ?: return "Gün ya da saat yok"
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val diff = dayNumber(c) - dayNumber(today)
        val day = when (diff) {
            0 -> "Bugün"
            1 -> "Yarın"
            else -> DAY_SHORT[dayIndex(c)]
        }
        return "$day ${hm(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))}"
    }

    companion object {
        const val ALL_DAYS = 0x7F
        const val WEEKDAYS = 0x1F
        const val WEEKEND = 0x60
        val DAY_SHORT = listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
        val DAY_LETTER = listOf("Pt", "Sa", "Ça", "Pe", "Cu", "Ct", "Pz")

        private val ICONS = listOf(
            listOf("diş") to "🪥",
            listOf("kitap", "oku") to "📖",
            listOf("su iç", "su ") to "💧",
            listOf("yatak", "yatağ") to "🛏️",
            listOf("spor", "koş", "yürü", "egzersiz", "antren") to "🏃",
            listOf("ilaç", "hap", "vitamin") to "💊",
            listOf("uyu", "yat") to "🌙",
            listOf("ders", "çalış", "ödev") to "📚",
            listOf("yemek", "kahvaltı") to "🍽️",
            listOf("namaz") to "🕌",
            listOf("meditasyon", "nefes") to "🧘",
            listOf("temizl", "topla") to "🧹",
            listOf("gitar", "piyano", "müzik") to "🎸",
        )

        fun dayIndex(c: Calendar) = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
        private fun dayNumber(c: Calendar) = c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)

        fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

        fun durText(m: Int): String = when {
            m <= 0 -> ""
            m % 60 == 0 -> "${m / 60} saat"
            m > 60 -> "${m / 60} sa ${m % 60} dk"
            else -> "$m dk"
        }

        fun untilText(at: Long, now: Long = System.currentTimeMillis()): String {
            val m = ((at - now + 59_999) / 60_000).toInt()
            return when {
                m <= 1 -> "şimdi"
                m < 60 -> "$m dk sonra"
                m < 24 * 60 -> if (m % 60 == 0) "${m / 60} saat sonra" else "${m / 60} sa ${m % 60} dk sonra"
                else -> "${m / (24 * 60)} gün sonra"
            }
        }

        fun fromJson(o: JSONObject): Reminder {
            val arr = o.optJSONArray("times") ?: JSONArray()
            return Reminder(
                id = o.getString("id"),
                title = o.optString("title"),
                days = o.optInt("days", ALL_DAYS),
                times = List(arr.length()) { arr.getInt(it) }.filter { it in 0 until 1440 }.distinct(),
                minutes = o.optInt("minutes"),
                enabled = o.optBoolean("enabled", true),
                updatedAt = o.optLong("updatedAt"),
                deleted = o.optBoolean("deleted"),
            )
        }

        /** "Günde N kez, başlangıç–bitiş arası" için eşit aralıklı saatler (5 dakikaya yuvarlı). */
        fun spread(count: Int, start: Int, end: Int): List<Int> {
            if (count <= 1) return listOf(start)
            val span = if (end > start) end - start else end + 1440 - start
            return List(count) { (start + span * it / (count - 1)) % 1440 }
                .map { (it + 2) / 5 * 5 % 1440 }.distinct().sorted()
        }

        /** Boş listede önerilen hazır hatırlatmalar. */
        fun presets() = listOf(
            Reminder(title = "Yatağı topla", times = listOf(8 * 60)),
            Reminder(title = "Diş fırçala", times = listOf(8 * 60 + 30, 22 * 60 + 30)),
            Reminder(title = "Kitap oku", times = listOf(21 * 60), minutes = 60),
            Reminder(title = "Su iç", times = spread(5, 9 * 60, 21 * 60)),
            Reminder(title = "İlaç / vitamin", times = listOf(9 * 60)),
            Reminder(title = "Spor", days = 0b0010101, times = listOf(18 * 60), minutes = 45),
        )
    }
}
