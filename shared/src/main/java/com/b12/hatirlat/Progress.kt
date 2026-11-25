package com.b12.hatirlat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs

/**
 * XP / seviye / seri. Her "yaptım" bir kayıt: (hatırlatma, o anki çalma zamanı) anahtarıyla bir kez sayılır,
 * telefon ve saatte aynı işe basılsa da çift sayılmaz. Toplam XP kayıtlardan hesaplanır, iki cihaz aynı sonucu bulur.
 */
data class Done(val rid: String, val at: Long, val doneAt: Long, val xp: Int, val title: String) {
    val key get() = "${rid}_$at"
    fun toJson(): JSONObject = JSONObject().put("rid", rid).put("at", at).put("doneAt", doneAt).put("xp", xp).put("title", title)

    companion object {
        fun fromJson(o: JSONObject) = Done(o.getString("rid"), o.getLong("at"), o.optLong("doneAt"), o.optInt("xp", 10), o.optString("title"))
    }
}

object Progress {
    private const val PREFS = "b12"
    private const val KEY = "done"
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())

    const val BASE_XP = 10
    const val ON_TIME_XP = 5
    const val STREAK_XP = 5          // seri günü başına, en çok 10 gün
    private const val ON_TIME_MS = 15 * 60_000L

    enum class Kind { NORMAL, TIMER }

    data class Stats(
        val xp: Int, val level: Int, val into: Int, val need: Int,
        val streak: Int, val todayXp: Int, val week: BooleanArray, val total: Int,
    ) {
        val title get() = title(level)
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(ctx: Context): MutableMap<String, Done> {
        val s = prefs(ctx).getString(KEY, null) ?: return mutableMapOf()
        return try {
            val o = JSONObject(s)
            val m = mutableMapOf<String, Done>()
            for (k in o.keys()) m[k] = Done.fromJson(o.getJSONObject(k))
            m
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun write(ctx: Context, m: Map<String, Done>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v.toJson())
        prefs(ctx).edit().putString(KEY, o.toString()).commit()
    }

    fun events(ctx: Context): List<Done> = synchronized(lock) { read(ctx).values.toList() }

    fun has(ctx: Context, rid: String, at: Long) = synchronized(lock) { read(ctx).containsKey("${rid}_$at") }

    fun xpFor(r: Reminder, at: Long, kind: Kind, now: Long = System.currentTimeMillis()): Int {
        var xp = BASE_XP
        if (kind == Kind.TIMER) xp += r.minutes / 3          // 1 saat kitap = +20
        if (abs(now - at) <= ON_TIME_MS || (kind == Kind.TIMER)) xp += ON_TIME_XP
        return xp
    }

    /** Bu cihazda yapıldı olarak işaretle. Kazanılan XP'yi döndürür, zaten sayıldıysa null. */
    fun record(ctx: Context, r: Reminder, at: Long, kind: Kind = Kind.NORMAL, toast: Boolean = true): Int? {
        val now = System.currentTimeMillis()
        val before = stats(ctx, now)
        val d = Done(r.id, at, now, xpFor(r, at, kind, now), r.title)
        val added = synchronized(lock) {
            val m = read(ctx)
            if (m.containsKey(d.key)) false else { m[d.key] = d; write(ctx, m); true }
        }
        if (!added) return null
        Sync.publishDone(ctx, d)
        val after = stats(ctx, now)
        val gained = after.xp - before.xp
        if (toast) main.post {
            val streak = if (after.streak > before.streak && after.streak > 1) "  🔥 ${after.streak} gün" else ""
            Toast.makeText(ctx.applicationContext, "+$gained XP$streak", Toast.LENGTH_SHORT).show()
        }
        if (after.level > before.level) Notifier.levelUp(ctx, after.level)
        Store.notifyChanged()
        return gained
    }

    /** Öbür cihazdan gelen kayıt. */
    fun merge(ctx: Context, d: Done): Boolean {
        val added = synchronized(lock) {
            val m = read(ctx)
            if (m.containsKey(d.key)) false else { m[d.key] = d; write(ctx, m); true }
        }
        if (added) {
            Notifier.cancel(ctx, d.rid)
            Store.notifyChanged()
        }
        return added
    }

    // ---- seviye eğrisi: 2. seviye 100 XP, sonra her seviye 50 XP daha fazla ister
    fun levelStart(l: Int) = 100 * (l - 1) + 25 * (l - 1) * (l - 2)

    fun levelOf(xp: Int): Int {
        var l = 1
        while (levelStart(l + 1) <= xp) l++
        return l
    }

    fun title(l: Int) = when {
        l >= 25 -> "B12 Efsanesi"
        l >= 20 -> "Usta"
        l >= 15 -> "Alışkanlık Canavarı"
        l >= 10 -> "Disiplinli"
        l >= 7 -> "Kararlı"
        l >= 4 -> "Düzenli"
        l >= 2 -> "Isınıyor"
        else -> "Çaylak"
    }

    private fun dayKey(t: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    private fun dayStart(t: Long, back: Int = 0): Long = Calendar.getInstance().apply {
        timeInMillis = t
        add(Calendar.DAY_OF_YEAR, -back)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun stats(ctx: Context, now: Long = System.currentTimeMillis()): Stats {
        val ev = events(ctx)
        val days = ev.map { dayKey(it.at) }.toSortedSet()
        // Seri bonusu: o güne kadar üst üste kaç gün yapıldıysa (en çok 10) × 5 XP, her gün bir kez.
        var bonus = 0
        var run = 0
        var prevDay: Long? = null
        val dayXp = HashMap<Int, Int>()
        for (k in days) {
            val start = ev.first { dayKey(it.at) == k }.let { dayStart(it.at) }
            run = if (prevDay != null && dayKey(prevDay + 36 * 3600_000L) == k) run + 1 else 1
            prevDay = start
            val b = (run - 1).coerceAtMost(10) * STREAK_XP
            bonus += b
            dayXp[k] = b
        }
        val xp = ev.sumOf { it.xp } + bonus
        val level = levelOf(xp)
        val today = dayKey(now)
        var streak = 0
        var back = if (today in days) 0 else 1
        while (dayKey(dayStart(now, back)) in days) { streak++; back++ }
        val week = BooleanArray(7) { dayKey(dayStart(now, 6 - it)) in days }
        val todayXp = ev.filter { dayKey(it.at) == today }.sumOf { it.xp } + (dayXp[today] ?: 0)
        return Stats(xp, level, xp - levelStart(level), levelStart(level + 1) - levelStart(level), streak, todayXp, week, ev.size)
    }

    // ---- bugün
    /** Bugünkü çalma anları (bugün seçili gün değilse boş). */
    fun todaySlots(r: Reminder, now: Long = System.currentTimeMillis()): List<Long> {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        if (!r.hasDay(Reminder.dayIndex(c))) return emptyList()
        return r.times.sorted().map { t ->
            Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, t / 60); set(Calendar.MINUTE, t % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
    }

    /** (bugün yapılan, bugün planlanan) */
    fun today(ctx: Context, r: Reminder, now: Long = System.currentTimeMillis()): Pair<Int, Int> {
        val slots = todaySlots(r, now)
        val m = synchronized(lock) { read(ctx) }
        return slots.count { m.containsKey("${r.id}_$it") } to slots.size
    }

    /** Elle işaretlenecek an: geçmiş ve yapılmamış en son, yoksa yaklaşan ilk. Hepsi bittiyse null. */
    fun slotToMark(ctx: Context, r: Reminder, now: Long = System.currentTimeMillis()): Long? {
        val m = synchronized(lock) { read(ctx) }
        val open = todaySlots(r, now).filter { !m.containsKey("${r.id}_$it") }
        return open.lastOrNull { it <= now } ?: open.firstOrNull()
    }

    /** Karttaki ✓: bugünün sıradaki işini yapıldı say. */
    fun markNow(ctx: Context, r: Reminder): Boolean {
        val at = slotToMark(ctx, r) ?: return false
        Notifier.cancel(ctx, r.id)
        record(ctx, r, at)
        return true
    }
}
