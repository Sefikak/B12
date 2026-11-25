package com.b12.hatirlat

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/** Her cihaz alarmlarını kendisi kurar: saat telefondan uzaktayken de zamanında titrer. */
object Alarms {
    const val FIRE = "com.b12.hatirlat.FIRE"
    const val SNOOZED = "com.b12.hatirlat.SNOOZED"
    const val ENDED = "com.b12.hatirlat.ENDED"
    const val DONE = "com.b12.hatirlat.DONE"
    const val SNOOZE = "com.b12.hatirlat.SNOOZE"
    const val START = "com.b12.hatirlat.START"
    const val DISMISSED = "com.b12.hatirlat.DISMISSED"
    const val TICK = "com.b12.hatirlat.TICK"
    const val DAY = "com.b12.hatirlat.DAY"
    const val SNOOZE_MIN = 10

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    fun intent(ctx: Context, action: String, id: String): Intent = Intent(ctx, AlarmReceiver::class.java)
        .setAction(action)
        .setData(Uri.parse("b12://${action.substringAfterLast('.')}/$id"))
        .putExtra("id", id)

    fun pending(ctx: Context, i: Intent): PendingIntent =
        PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(ctx: Context, r: Reminder, from: Long = System.currentTimeMillis()) {
        val i = intent(ctx, FIRE, r.id)
        am(ctx).cancel(pending(ctx, i))
        if (r.deleted || !r.enabled) {
            cancelLater(ctx, r.id)
            return
        }
        val at = r.nextAfter(from) ?: return
        i.putExtra("at", at)
        setExact(ctx, at, pending(ctx, i))
    }

    /** [occ]: bildirimin ait olduğu asıl çalma anı (XP anahtarı ve gösterilen saat). */
    fun later(ctx: Context, id: String, at: Long, action: String, occ: Long = at) {
        setExact(ctx, at, pending(ctx, intent(ctx, action, id).putExtra("at", at).putExtra("occ", occ)))
    }

    /** Bu çalma için ısrar sürsün mü: yapılmadıysa, aynı gün içindeyse ve sıradaki saat henüz gelmediyse. */
    fun stillDue(ctx: Context, r: Reminder, occ: Long, now: Long = System.currentTimeMillis()): Boolean {
        if (r.deleted || !r.enabled || Progress.has(ctx, r.id, occ) || Progress.dayComplete(ctx, r, occ)) return false
        val next = r.nextAfter(occ)
        if (next != null && next <= now) return false
        val a = java.util.Calendar.getInstance().apply { timeInMillis = occ }
        val b = java.util.Calendar.getInstance().apply { timeInMillis = now }
        return a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR) &&
            a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
    }

    fun cancelLater(ctx: Context, id: String) {
        for (a in listOf(SNOOZED, ENDED)) am(ctx).cancel(pending(ctx, intent(ctx, a, id)))
    }

    fun rescheduleAll(ctx: Context) {
        Store.all(ctx).forEach { schedule(ctx, it) }
        scheduleDay(ctx)
    }

    /**
     * Gün dönümünde widget, Tile ve kadran yenilensin (yoksa sabah dünkü "tamam"ı gösterir).
     * Uyandırmayan alarm: ekran kapalıysa cihaz uyanınca teslim edilir, pil harcamaz.
     */
    fun scheduleDay(ctx: Context) {
        val c = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 5); set(java.util.Calendar.MILLISECOND, 0)
        }
        am(ctx).setWindow(AlarmManager.RTC, c.timeInMillis, 5 * 60_000L, pending(ctx, intent(ctx, DAY, "day")))
    }

    /** Ekrandaki özetleri hemen yenile ve sonraki gün dönümünü kur. */
    fun refreshDay(ctx: Context) {
        Store.all(ctx)  // Store'un uygulama bağlamını ayarlar
        Store.notifyChanged()
        scheduleDay(ctx)
    }

    fun snoozeMin(r: Reminder?) = if (r != null && r.nag) r.nagMin else SNOOZE_MIN

    fun canExact(ctx: Context) = Build.VERSION.SDK_INT < 31 || am(ctx).canScheduleExactAlarms()

    private fun setExact(ctx: Context, at: Long, pi: PendingIntent) {
        val am = am(ctx)
        if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action == Alarms.DAY) return Alarms.refreshDay(ctx)
        val id = i.getStringExtra("id") ?: return
        val r = Store.get(ctx, id)
        val now = System.currentTimeMillis()
        val at = i.getLongExtra("at", now)
        val occ = i.getLongExtra("occ", at)
        when (i.action) {
            Alarms.FIRE -> if (r != null && r.enabled && !r.deleted) {
                // Telefon kapalıyken kaçan hatırlatmayı saatler sonra çalma.
                if (now - at < 30 * 60_000L && !Progress.has(ctx, r.id, at) && !Progress.dayComplete(ctx, r, at)) Notifier.remind(ctx, r, at)
                Alarms.schedule(ctx, r, maxOf(now, at))
            }
            Alarms.SNOOZED -> if (r != null && Alarms.stillDue(ctx, r, occ, now)) Notifier.remind(ctx, r, occ)
            Alarms.DISMISSED -> if (r != null && r.nag && Alarms.stillDue(ctx, r, occ, now)) {
                // Kaydırılıp kapatıldı: yapılmadıysa birazdan geri gelsin (sessiz saatlerde değil).
                val t = now + r.nagMin * 60_000L
                if (!Settings.get(ctx).isQuiet(t)) Alarms.later(ctx, id, t, Alarms.SNOOZED, occ)
            }
            Alarms.TICK -> if (r != null) Progress.markNow(ctx, r)
            Alarms.ENDED -> if (r != null && !r.deleted) {
                val xp = Progress.record(ctx, r, at, Progress.Kind.TIMER, toast = false)
                Notifier.ended(ctx, r, xp)
            }
            Alarms.DONE -> {
                Notifier.cancel(ctx, id)
                if (r != null && i.hasExtra("at")) Progress.record(ctx, r, occ)
                Alarms.cancelLater(ctx, id)
                relay(ctx, "/n/done", id)
            }
            Alarms.SNOOZE -> {
                val t = now + Alarms.snoozeMin(r) * 60_000L
                Notifier.cancel(ctx, id)
                Alarms.later(ctx, id, t, Alarms.SNOOZED, occ)
                relay(ctx, "/n/snooze", "$id|$t|$occ")
            }
            Alarms.START -> if (r != null && r.minutes > 0) {
                val t = now + r.minutes * 60_000L
                Notifier.started(ctx, r, t)
                Alarms.later(ctx, id, t, Alarms.ENDED)
                relay(ctx, "/n/start", "$id|$t")
            }
        }
    }

    private fun relay(ctx: Context, path: String, data: String) {
        val pr = goAsync()
        Sync.send(ctx, path, data) { pr.finish() }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Alarms.rescheduleAll(ctx)
        Alarms.refreshDay(ctx)  // saat/saat dilimi değiştiyse "bugün" de değişmiş olabilir
    }
}
