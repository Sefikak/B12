package com.b12.hatirlat

import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp as pdp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Saat uygulaması: değişiklikte Tile ve kadran göstergesini yenile. */
class B12WearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.onChangeHook = { ctx ->
            try { TileService.getUpdater(ctx).requestUpdate(TodayTile::class.java) } catch (_: Exception) {}
            try {
                ComplicationDataSourceUpdateRequester.create(ctx, ComponentName(ctx, TodayComplication::class.java)).requestUpdateAll()
            } catch (_: Exception) {}
        }
        Alarms.scheduleDay(this)
    }
}

/** Bugünün özeti: (yapılan tik, toplam hedef, kalan iş sayısı) */
private fun todaySummary(ctx: Context): Triple<Int, Int, Int> {
    var done = 0; var total = 0; var left = 0
    for (r in Store.visible(ctx).filter { it.enabled }) {
        val (d, t) = Progress.today(ctx, r)
        if (t == 0) continue
        done += d; total += t
        if (d < t) left++
    }
    return Triple(done, total, left)
}

/** Kaydırınca açılan kart: bugünün işleri, dokununca tik. */
class TodayTile : TileService() {
    private val accent = 0xFF8AB4FF.toInt()
    private val text = 0xFFECEFF4.toInt()
    private val dim = 0xFF96A0AE.toInt()
    private val card = 0xFF1A1F26.toInt()

    override fun onTileRequest(req: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val clicked = req.currentState.lastClickableId
        if (clicked.startsWith("tick:")) {
            Store.get(this, clicked.removePrefix("tick:"))?.let { Progress.markNow(this, it) }
        }
        val now = System.currentTimeMillis()
        val s = Progress.stats(this, now)
        val items = Store.visible(this).filter { it.enabled }
            .map { it to Progress.today(this, it, now) }
            .filter { it.second.second > 0 }
            .sortedWith(compareBy({ it.second.first >= it.second.second }, { r -> r.first.nextAfter(now) ?: Long.MAX_VALUE }))
            .take(4)

        val col = LayoutElementBuilders.Column.Builder().setWidth(expand()).setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        col.addContent(txt("Sv ${s.level}" + if (s.streak > 0) "  🔥${s.streak}" else "", 13f, accent, bold = true))
        col.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(pdp(6f)).build())
        if (items.isEmpty()) {
            col.addContent(txt("Bugün iş yok 🎉", 14f, dim))
        }
        for ((r, t) in items) {
            val done = t.first >= t.second
            val label = (if (done) "✓ " else "○ ") + r.title + if (t.second > 1 && !done) "  ${t.first}/${t.second}" else ""
            col.addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(expand()).setHeight(pdp(34f))
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(if (done) 0xFF253A55.toInt() else card))
                                .setCorner(ModifiersBuilders.Corner.Builder().setRadius(pdp(17f)).build()).build())
                            .setPadding(ModifiersBuilders.Padding.Builder().setStart(pdp(12f)).setEnd(pdp(12f)).build())
                            .setClickable(ModifiersBuilders.Clickable.Builder().setId("tick:${r.id}")
                                .setOnClick(ActionBuilders.LoadAction.Builder().build()).build())
                            .build(),
                    )
                    .addContent(txt(label, 13f, if (done) accent else text, bold = !done))
                    .build(),
            )
            col.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(pdp(4f)).build())
        }
        val root = LayoutElementBuilders.Box.Builder().setWidth(expand()).setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder()
                .setPadding(ModifiersBuilders.Padding.Builder().setStart(pdp(22f)).setEnd(pdp(22f)).build())
                .setClickable(ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(
                    ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName("com.b12.hatirlat.MainActivity").build(),
                    ).build(),
                ).build())
                .build())
            .addContent(col.build())
            .build()
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setFreshnessIntervalMillis(15 * 60_000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(req: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("1").build())

    private fun txt(s: String, size: Float, color: Int, bold: Boolean = false): LayoutElementBuilders.Text =
        LayoutElementBuilders.Text.Builder().setText(s).setMaxLines(1)
            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color))
                .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL).build())
            .build()
}

/** Kadran göstergesi: kısa metin "🔥5 2/3" ya da bugünün ilerleme halkası. */
class TodayComplication : ComplicationDataSourceService() {
    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        listener.onComplicationData(build(request.complicationType))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, preview = true)

    private fun build(type: ComplicationType, preview: Boolean = false): ComplicationData? {
        val (done, total, _) = if (preview) Triple(2, 3, 1) else todaySummary(this)
        val streak = if (preview) 5 else Progress.stats(this).streak
        val tap = packageManager.getLaunchIntentForPackage(packageName)?.let {
            android.app.PendingIntent.getActivity(this, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE)
        }
        val desc = PlainComplicationText.Builder("B12: bugün $done/$total, seri $streak gün").build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                PlainComplicationText.Builder(if (total == 0) "🔥$streak" else "$done/$total").build(), desc,
            ).setTitle(PlainComplicationText.Builder(if (total == 0) "B12" else "🔥$streak").build()).setTapAction(tap).build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                done.toFloat(), 0f, total.coerceAtLeast(1).toFloat(), desc,
            ).setText(PlainComplicationText.Builder("$done/$total").build())
                .setTitle(PlainComplicationText.Builder("🔥$streak").build()).setTapAction(tap).build()
            else -> null
        }
    }
}
