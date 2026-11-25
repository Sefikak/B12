package com.b12.hatirlat

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews

/** Ana ekran widget'ı: bugünün işleri ve ✓ düğmeleri. */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = render(ctx, mgr, ids)

    companion object {
        private val ROWS = listOf(
            Triple(R.id.row0, R.id.title0, R.id.count0) to R.id.check0,
            Triple(R.id.row1, R.id.title1, R.id.count1) to R.id.check1,
            Triple(R.id.row2, R.id.title2, R.id.count2) to R.id.check2,
            Triple(R.id.row3, R.id.title3, R.id.count3) to R.id.check3,
            Triple(R.id.row4, R.id.title4, R.id.count4) to R.id.check4,
        )

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, TodayWidget::class.java))
            if (ids.isNotEmpty()) render(ctx, mgr, ids)
        }

        fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val now = System.currentTimeMillis()
            val items = Store.visible(ctx).filter { it.enabled }
                .map { it to Progress.today(ctx, it, now) }
                .filter { it.second.second > 0 }
                // önce bitmemişler, sonra sıradaki saate göre
                .sortedWith(compareBy({ it.second.first >= it.second.second }, { r -> r.first.nextAfter(now) ?: Long.MAX_VALUE }))
            val s = Progress.stats(ctx, now)
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val v = RemoteViews(ctx.packageName, R.layout.widget_today)
            v.setOnClickPendingIntent(R.id.root, open)
            val left = items.count { it.second.first < it.second.second }
            v.setTextViewText(R.id.head, if (items.isEmpty()) "B12 · Bugün" else if (left == 0) "B12 · Bugün tamam ✓" else "B12 · $left iş kaldı")
            v.setTextViewText(R.id.streak, "Sv ${s.level}" + if (s.streak > 0) "  🔥${s.streak}" else "")
            v.setViewVisibility(R.id.empty, if (items.isEmpty()) View.VISIBLE else View.GONE)
            for ((i, row) in ROWS.withIndex()) {
                val (ids3, check) = row
                val (rowId, titleId, countId) = ids3
                val it = items.getOrNull(i)
                if (it == null) { v.setViewVisibility(rowId, View.GONE); continue }
                val (r, t) = it
                val done = t.first >= t.second
                v.setViewVisibility(rowId, View.VISIBLE)
                v.setTextViewText(titleId, "${r.icon()}  ${r.title}")
                v.setTextViewText(countId, if (done) "" else if (t.second > 1) "${t.first}/${t.second}" else r.nextText(now).substringAfter(' '))
                v.setInt(check, "setBackgroundResource", if (done) R.drawable.check_on else R.drawable.check_off)
                v.setTextColor(check, if (done) 0xFF0F1216.toInt() else 0xFF8AB4FF.toInt())
                v.setOnClickPendingIntent(check, Alarms.pending(ctx, Alarms.intent(ctx, Alarms.TICK, r.id)))
            }
            mgr.updateAppWidget(ids, v)
        }
    }
}

/** Uygulama: değişiklikte widget'ı yenile. */
class B12App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.onChangeHook = { ctx -> TodayWidget.refresh(ctx) }
        Alarms.scheduleDay(this)
    }
}
