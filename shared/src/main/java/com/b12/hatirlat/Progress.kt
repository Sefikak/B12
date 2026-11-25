package com.b12.hatirlat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.abs

/**
 * XP / seviye / seri / rozet. Her tik bir kayıt: (hatırlatma, o anki çalma zamanı) anahtarıyla bir kez sayılır,
 * telefon ve saatte aynı işe basılsa da çift sayılmaz. Her şey kayıtlardan hesaplanır, iki cihaz aynı sonucu bulur.
 */
data class Done(val rid: String, val at: Long, val doneAt: Long, val xp: Int, val title: String, val min: Int = 0) {
    val key get() = "${rid}_$at"
    fun toJson(): JSONObject = JSONObject().put("rid", rid).put("at", at).put("doneAt", doneAt).put("xp", xp)
        .put("title", title).put("min", min)

    companion object {
        fun fromJson(o: JSONObject) = Done(
            o.getString("rid"), o.getLong("at"), o.optLong("doneAt"), o.optInt("xp", 10), o.optString("title"), o.optInt("min"),
        )
    }
}

data class Badge(val id: String, val icon: String, val name: String, val desc: String, val progress: Int, val goal: Int) {
    val unlocked get() = progress >= goal
}

object Progress {
    private const val PREFS = "b12"
    private const val KEY = "done"
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private const val DAY = 24 * 3600_000L

    const val BASE_XP = 10
    const val ON_TIME_XP = 5
    const val STREAK_XP = 5          // seri günü başına, en çok 10 gün
    const val MAX_FREEZE = 2
    private const val ON_TIME_MS = 15 * 60_000L

    enum class Kind { NORMAL, TIMER }

    data class Stats(
        val xp: Int, val level: Int, val into: Int, val need: Int,
        val streak: Int, val best: Int, val freezes: Int,
        val todayXp: Int,
        /** son 7 gün: 0 boş, 1 yapıldı, 2 seri koruyucuyla kurtarıldı */
        val week: IntArray,
        val total: Int,
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
        val d = Done(r.id, at, now, xpFor(r, at, kind, now), r.title, if (kind == Kind.TIMER) r.minutes else 0)
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
        for (b in newBadges(ctx)) Notifier.badge(ctx, b)
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
            Alarms.cancelLater(ctx, d.rid)
            newBadges(ctx)   // öbür cihaz zaten haber verdi: burada sessizce "görüldü" say
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

    // ---- gün yardımcıları
    fun dayKey(t: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    /** [t] gününün başı; back gün geriye (eksi değer ileri). */
    fun dayStart(t: Long, back: Int = 0): Long = Calendar.getInstance().apply {
        timeInMillis = t
        add(Calendar.DAY_OF_YEAR, -back)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun weekStart(t: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = dayStart(t) }
        c.add(Calendar.DAY_OF_YEAR, -Reminder.dayIndex(c))
        return c.timeInMillis
    }

    private data class Walk(val bonus: Map<Int, Int>, val streak: Int, val best: Int, val freezes: Int, val frozen: Set<Int>)

    /**
     * Günleri baştan bugüne yürür: üst üste günler seri, her 7 günde 1 seri koruyucu (en çok 2) kazanılır,
     * kaçırılan tek gün koruyucu varsa seriyi bozmaz. Bugün henüz yapılmadıysa seri bozulmuş sayılmaz.
     */
    private fun walk(days: Set<Int>, first: Long, now: Long): Walk {
        val bonus = HashMap<Int, Int>()
        val frozen = HashSet<Int>()
        var run = 0; var best = 0; var freezes = 0
        val today = dayKey(now)
        val c = Calendar.getInstance().apply { timeInMillis = dayStart(first) }
        var guard = 0
        while (guard++ < 3000) {
            val k = dayKey(c.timeInMillis)
            if (k > today) break
            if (k in days) {
                run++
                bonus[k] = (run - 1).coerceAtMost(10) * STREAK_XP
                if (run % 7 == 0) freezes = (freezes + 1).coerceAtMost(MAX_FREEZE)
                best = maxOf(best, run)
            } else if (k != today) {
                if (run > 0 && freezes > 0) { freezes--; frozen.add(k) } else run = 0
            }
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return Walk(bonus, run, best, freezes, frozen)
    }

    fun stats(ctx: Context, now: Long = System.currentTimeMillis()): Stats {
        val ev = events(ctx)
        if (ev.isEmpty()) return Stats(0, 1, 0, levelStart(2), 0, 0, 0, 0, IntArray(7), 0)
        val days = ev.map { dayKey(it.at) }.toSet()
        val w = walk(days, ev.minOf { it.at }, now)
        val xp = ev.sumOf { it.xp } + w.bonus.values.sum()
        val level = levelOf(xp)
        val today = dayKey(now)
        val week = IntArray(7) {
            val k = dayKey(dayStart(now, 6 - it))
            when (k) { in days -> 1; in w.frozen -> 2; else -> 0 }
        }
        val todayXp = ev.filter { dayKey(it.at) == today }.sumOf { it.xp } + (w.bonus[today] ?: 0)
        return Stats(xp, level, xp - levelStart(level), levelStart(level + 1) - levelStart(level), w.streak, w.best, w.freezes, todayXp, week, ev.size)
    }

    // ---- bugün
    /** Bugünkü çalma anları (bugün etkin gün değilse boş). */
    fun todaySlots(r: Reminder, now: Long = System.currentTimeMillis()): List<Long> {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        if (!r.activeOn(c)) return emptyList()
        return r.times.sorted().map { t ->
            Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, t / 60); set(Calendar.MINUTE, t % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
    }

    private fun doneOnDay(m: Map<String, Done>, r: Reminder, t: Long): List<Done> {
        val d0 = dayStart(t)
        val d1 = dayStart(t, -1)
        return m.values.filter { it.rid == r.id && it.at >= d0 && it.at < d1 }
    }

    /** Bu hafta (Pzt–Paz) bu hatırlatmanın tik sayısı. */
    fun weekTicks(ctx: Context, r: Reminder, t: Long = System.currentTimeMillis()): Int {
        val w0 = weekStart(t)
        val w1 = Calendar.getInstance().apply { timeInMillis = w0; add(Calendar.DAY_OF_YEAR, 7) }.timeInMillis
        return events(ctx).count { it.rid == r.id && it.at >= w0 && it.at < w1 }
    }

    fun weekComplete(ctx: Context, r: Reminder, t: Long) = r.weekly > 0 && weekTicks(ctx, r, t) >= r.weekly

    /** (bugün atılan tik, günlük hedef). Bugün etkin gün değilse hedef 0. */
    fun today(ctx: Context, r: Reminder, now: Long = System.currentTimeMillis()): Pair<Int, Int> {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        if (!r.activeOn(c)) return 0 to 0
        val m = synchronized(lock) { read(ctx) }
        val done = doneOnDay(m, r, now).size
        if (weekComplete(ctx, r, now)) return r.goal to r.goal
        return done.coerceAtMost(r.goal) to r.goal
    }

    /** O günün (ya da haftanın) hedefi tamam mı? Bildirim ve ısrar bunu sorar. */
    fun dayComplete(ctx: Context, r: Reminder, at: Long): Boolean {
        if (weekComplete(ctx, r, at)) return true
        val m = synchronized(lock) { read(ctx) }
        return doneOnDay(m, r, at).size >= r.goal
    }

    fun slotDone(ctx: Context, r: Reminder, at: Long) = has(ctx, r.id, at)

    /** Elle tik için an: geçmiş ve tiklenmemiş en son saat, yoksa yaklaşan ilk; saatler bittiyse ek tik. */
    fun slotToMark(ctx: Context, r: Reminder, now: Long = System.currentTimeMillis()): Long? {
        val m = synchronized(lock) { read(ctx) }
        if (dayComplete(ctx, r, now)) return null
        val open = todaySlots(r, now).filter { !m.containsKey("${r.id}_$it") }
        open.lastOrNull { it <= now }?.let { return it }
        open.firstOrNull()?.let { return it }
        // Hedef saat sayısından fazla: günün başına göre ek anahtarlar (00:00:00.001, .002 …)
        val d0 = dayStart(now)
        return (1..50).map { d0 + it }.first { !m.containsKey("${r.id}_$it") }
    }

    /** Karttaki ✓: bugünün sıradaki tikini at. */
    fun markNow(ctx: Context, r: Reminder): Boolean = markAt(ctx, r, slotToMark(ctx, r))

    /** Belirli bir saati tikle (karttaki saat çipi). */
    fun markAt(ctx: Context, r: Reminder, at: Long?): Boolean {
        if (at == null || has(ctx, r.id, at)) return false
        if (dayComplete(ctx, r, at)) return false
        Notifier.cancel(ctx, r.id)
        Alarms.cancelLater(ctx, r.id)
        record(ctx, r, at)
        return true
    }

    // ---- alışkanlık başına
    /** Üst üste kaç etkin gün hedef tutturuldu (bugün henüz tamam değilse dünden sayar). */
    fun habitStreak(ctx: Context, r: Reminder, now: Long = System.currentTimeMillis()): Int {
        if (r.isOnce || r.weekly > 0) return 0
        val m = synchronized(lock) { read(ctx) }
        var n = 0
        val c = Calendar.getInstance().apply { timeInMillis = now }
        for (i in 0 until 400) {
            if (r.activeOn(c)) {
                val ok = doneOnDay(m, r, c.timeInMillis).size >= r.goal
                if (ok) n++ else if (i > 0) break
            }
            c.add(Calendar.DAY_OF_YEAR, -1)
        }
        return n
    }

    /** Son [days] günde hedefin tutturulduğu etkin günlerin oranı (0..100); etkin gün yoksa null. */
    fun completion(ctx: Context, r: Reminder, days: Int = 30, now: Long = System.currentTimeMillis()): Int? {
        val m = synchronized(lock) { read(ctx) }
        // Alışkanlık ilk tikinden önceki günleri sayma (yeni eklenene %3 demesin).
        val since = m.values.filter { it.rid == r.id }.minOfOrNull { it.at }?.let { dayStart(it) } ?: return null
        var active = 0; var ok = 0
        val c = Calendar.getInstance().apply { timeInMillis = now }
        for (i in 0 until days) {
            if (c.timeInMillis < since) break
            if (r.activeOn(c)) {
                val done = doneOnDay(m, r, c.timeInMillis).size >= r.goal
                if (i > 0 || done) { active++; if (done) ok++ }
            }
            c.add(Calendar.DAY_OF_YEAR, -1)
        }
        return if (active == 0) null else ok * 100 / active
    }

    /** Gün başına tik sayısı (ısı haritası). */
    fun ticksByDay(ctx: Context): Map<Int, Int> = events(ctx).groupingBy { dayKey(it.at) }.eachCount()

    /** Son [weeks] haftanın XP'si (eskiden yeniye). */
    fun weeklyXp(ctx: Context, weeks: Int = 8, now: Long = System.currentTimeMillis()): List<Int> {
        val ev = events(ctx)
        val w0 = weekStart(now)
        return (weeks - 1 downTo 0).map { back ->
            val s = Calendar.getInstance().apply { timeInMillis = w0; add(Calendar.DAY_OF_YEAR, -7 * back) }.timeInMillis
            val e = Calendar.getInstance().apply { timeInMillis = s; add(Calendar.DAY_OF_YEAR, 7) }.timeInMillis
            ev.filter { it.at in s until e }.sumOf { it.xp }
        }
    }

    // ---- rozetler
    fun badges(ctx: Context, now: Long = System.currentTimeMillis()): List<Badge> {
        val ev = events(ctx)
        val s = stats(ctx, now)
        val early = ev.count { Calendar.getInstance().apply { timeInMillis = it.doneAt }.get(Calendar.HOUR_OF_DAY) < 8 }
        val late = ev.count { Calendar.getInstance().apply { timeInMillis = it.doneAt }.get(Calendar.HOUR_OF_DAY) >= 22 }
        val focusMin = ev.sumOf { it.min }
        val focusCount = ev.count { it.min > 0 }
        val perfect = perfectDays(ctx, now)
        val habits = ev.map { it.rid }.toSet().size
        return listOf(
            Badge("first", "🌱", "İlk adım", "İlk tikini at", ev.size, 1),
            Badge("s3", "🔥", "Isındım", "3 gün üst üste", s.best, 3),
            Badge("s7", "📅", "Bir hafta", "7 gün üst üste", s.best, 7),
            Badge("s30", "🏆", "Bir ay", "30 gün üst üste", s.best, 30),
            Badge("s100", "💯", "Yüz gün", "100 gün üst üste", s.best, 100),
            Badge("t50", "✅", "Elli", "50 tik", ev.size, 50),
            Badge("t250", "🎯", "İki yüz elli", "250 tik", ev.size, 250),
            Badge("t1000", "🌟", "Bin", "1000 tik", ev.size, 1000),
            Badge("early", "🐦", "Erken kuş", "Sabah 8'den önce 30 tik", early, 30),
            Badge("late", "🦉", "Gece kuşu", "Saat 22'den sonra 30 tik", late, 30),
            Badge("focus1", "⏱️", "Odak", "Bir süreli işi bitir", focusCount, 1),
            Badge("focus30", "📚", "Kitap kurdu", "Toplam 30 saat süreli iş", focusMin / 60, 30),
            Badge("perfect1", "✨", "Kusursuz gün", "Bir günün bütün hedefleri", perfect, 1),
            Badge("perfect7", "💎", "Kusursuz yedi", "7 kusursuz gün", perfect, 7),
            Badge("habits5", "🧩", "Çok yönlü", "5 farklı alışkanlıkta tik", habits, 5),
            Badge("lv5", "⭐", "Düzenli", "5. seviye", s.level, 5),
            Badge("lv10", "👑", "Disiplinli", "10. seviye", s.level, 10),
            Badge("freeze", "🧊", "Kurtarıcı", "Seri koruyucuyla serini kurtar", if (s.week.contains(2)) 1 else 0, 1),
        )
    }

    /** Son 120 günde, o gün etkin olan bütün hatırlatmaların hedefi tutturulmuş gün sayısı. */
    private fun perfectDays(ctx: Context, now: Long): Int {
        val rs = Store.visible(ctx).filter { it.enabled }
        if (rs.isEmpty()) return 0
        val m = synchronized(lock) { read(ctx) }
        var n = 0
        val c = Calendar.getInstance().apply { timeInMillis = now }
        for (i in 0 until 120) {
            val active = rs.filter { it.activeOn(c) && it.weekly == 0 }
            if (active.isNotEmpty() && active.all { doneOnDay(m, it, c.timeInMillis).size >= it.goal }) n++
            c.add(Calendar.DAY_OF_YEAR, -1)
        }
        return n
    }

    /** Yeni açılan rozetler (bir kez döner, sonra "görüldü" sayılır). */
    private fun newBadges(ctx: Context): List<Badge> {
        val p = prefs(ctx)
        val seen = p.getStringSet("badgesSeen", emptySet())!!.toMutableSet()
        val first = !p.contains("badgesSeen")
        val unlocked = badges(ctx).filter { it.unlocked }
        val fresh = unlocked.filter { it.id !in seen }
        if (fresh.isEmpty() && !first) return emptyList()
        seen.addAll(unlocked.map { it.id })
        p.edit().putStringSet("badgesSeen", seen).apply()
        return fresh
    }
}

/** Alt adım kontrol listesi: günlük, cihazda tutulur. Hepsi işaretlenince o günün tiki atılır. */
object Steps {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("b12_steps", Context.MODE_PRIVATE)
    private fun key(r: Reminder, now: Long) = "${r.id}_${Progress.dayKey(now)}"

    fun mask(ctx: Context, r: Reminder, now: Long = System.currentTimeMillis()) = prefs(ctx).getInt(key(r, now), 0)

    fun toggle(ctx: Context, r: Reminder, i: Int) {
        val now = System.currentTimeMillis()
        val m = mask(ctx, r, now) xor (1 shl i)
        prefs(ctx).edit().putInt(key(r, now), m).apply()
        val all = (1 shl r.steps.size) - 1
        if (r.steps.isNotEmpty() && m and all == all) Progress.markNow(ctx, r) else Store.notifyChanged()
    }
}
