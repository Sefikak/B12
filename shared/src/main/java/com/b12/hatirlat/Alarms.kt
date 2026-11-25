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

    fun later(ctx: Context, id: String, at: Long, action: String) {
        setExact(ctx, at, pending(ctx, intent(ctx, action, id).putExtra("at", at)))
    }

    fun cancelLater(ctx: Context, id: String) {
        for (a in listOf(SNOOZED, ENDED)) am(ctx).cancel(pending(ctx, intent(ctx, a, id)))
    }

    fun rescheduleAll(ctx: Context) {
        Store.all(ctx).forEach { schedule(ctx, it) }
    }

    fun canExact(ctx: Context) = Build.VERSION.SDK_INT < 31 || am(ctx).canScheduleExactAlarms()

    private fun setExact(ctx: Context, at: Long, pi: PendingIntent) {
        val am = am(ctx)
        if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getStringExtra("id") ?: return
        val r = Store.get(ctx, id)
        val now = System.currentTimeMillis()
        val at = i.getLongExtra("at", now)
        when (i.action) {
            Alarms.FIRE -> if (r != null && r.enabled && !r.deleted) {
                // Telefon kapalıyken kaçan hatırlatmayı saatler sonra çalma.
                if (now - at < 30 * 60_000L && !Progress.has(ctx, r.id, at)) Notifier.remind(ctx, r, at)
                Alarms.schedule(ctx, r, maxOf(now, at))
            }
            Alarms.SNOOZED -> if (r != null && !r.deleted) Notifier.remind(ctx, r, at)
            Alarms.ENDED -> if (r != null && !r.deleted) {
                val xp = Progress.record(ctx, r, at, Progress.Kind.TIMER, toast = false)
                Notifier.ended(ctx, r, xp)
            }
            Alarms.DONE -> {
                Notifier.cancel(ctx, id)
                if (r != null && i.hasExtra("at")) Progress.record(ctx, r, at)
                Alarms.cancelLater(ctx, id)
                relay(ctx, "/n/done", id)
            }
            Alarms.SNOOZE -> {
                val t = now + Alarms.SNOOZE_MIN * 60_000L
                Notifier.cancel(ctx, id)
                Alarms.later(ctx, id, t, Alarms.SNOOZED)
                relay(ctx, "/n/snooze", "$id|$t")
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
    override fun onReceive(ctx: Context, i: Intent) = Alarms.rescheduleAll(ctx)
}
