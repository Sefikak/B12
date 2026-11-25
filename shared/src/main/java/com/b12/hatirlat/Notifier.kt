package com.b12.hatirlat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.b12.hatirlat.shared.R
import java.util.Calendar

object Notifier {
    const val CH = "hatirlatma"
    const val CH_TIMER = "sure"
    private val VIBE = longArrayOf(0, 350, 200, 350)

    fun isWatch(ctx: Context) = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)

    fun ensure(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Hatırlatmalar", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = VIBE
        })
        nm.createNotificationChannel(NotificationChannel(CH_TIMER, "Süre sayacı", NotificationManager.IMPORTANCE_LOW))
    }

    private fun nid(id: String) = id.hashCode()

    private fun openApp(ctx: Context): PendingIntent? {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(ctx: Context, act: String, id: String, at: Long? = null) =
        Alarms.pending(ctx, Alarms.intent(ctx, act, id).also { if (at != null) it.putExtra("at", at).putExtra("occ", at) })

    private fun base(ctx: Context, r: Reminder, ch: String) = NotificationCompat.Builder(ctx, ch)
        .setSmallIcon(R.drawable.ic_b12)
        .setColor(0xFF8AB4FF.toInt())
        .setContentTitle("${r.icon()}  ${r.title.ifBlank { "Hatırlatma" }}")
        .setContentIntent(openApp(ctx))
        // Saatte B12 varsa telefondaki bildirim saate köprülenmesin: saat kendi bildirimini gösterir (çift titreme olmasın).
        // Saatte B12 yoksa köprülensin ki saat yine titresin.
        .setLocalOnly(isWatch(ctx) || Sync.peerHasApp(ctx))

    fun remind(ctx: Context, r: Reminder, at: Long) {
        ensure(ctx)
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val time = Reminder.hm(c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))
        var text = if (r.minutes > 0) "$time · ${r.durationText()}" else time
        if (r.nag) text += " · yapana kadar"
        val b = base(ctx, r, CH)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(VIBE)
            .setAutoCancel(!r.nag)
            .setWhen(at)
            .setShowWhen(true)
            .setDeleteIntent(action(ctx, Alarms.DISMISSED, r.id, at))
        if (r.minutes > 0) b.addAction(0, "Başla (${r.durationText()})", action(ctx, Alarms.START, r.id))
        b.addAction(0, "Yaptım ✓", action(ctx, Alarms.DONE, r.id, at))
            .addAction(0, "${Alarms.snoozeMin(r)} dk ertele", action(ctx, Alarms.SNOOZE, r.id, at))
        post(ctx, r.id, b)
    }

    fun started(ctx: Context, r: Reminder, end: Long) {
        ensure(ctx)
        val b = base(ctx, r, CH_TIMER)
            .setContentText("${r.durationText()} · bitince haber vereceğim")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(end)
            .setShowWhen(true)
            .addAction(0, "Bitir", action(ctx, Alarms.DONE, r.id, end))
        post(ctx, r.id, b)
    }

    fun ended(ctx: Context, r: Reminder, xp: Int?) {
        ensure(ctx)
        val b = base(ctx, r, CH)
            .setContentTitle("Süre doldu ✓")
            .setContentText("${r.title} · ${r.durationText()}" + (xp?.let { " · +$it XP" } ?: ""))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(VIBE)
            .setAutoCancel(true)
        post(ctx, r.id, b)
    }

    fun levelUp(ctx: Context, level: Int) {
        ensure(ctx)
        val b = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_b12)
            .setColor(0xFF8AB4FF.toInt())
            .setContentTitle("🎉 Seviye $level!")
            .setContentText("Yeni unvan: ${Progress.title(level)}")
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setLocalOnly(isWatch(ctx) || Sync.peerHasApp(ctx))
        try {
            NotificationManagerCompat.from(ctx).notify(LEVEL_ID, b.build())
        } catch (_: SecurityException) {
        }
    }

    private const val LEVEL_ID = 0x0B12

    fun cancel(ctx: Context, id: String) = NotificationManagerCompat.from(ctx).cancel(nid(id))

    private fun post(ctx: Context, id: String, b: NotificationCompat.Builder) {
        try {
            NotificationManagerCompat.from(ctx).notify(nid(id), b.build())
        } catch (_: SecurityException) {
        }
    }
}
