package com.b12.hatirlat

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Telefon ↔ saat eşitlemesi (Wearable Data Layer, Bluetooth/Wi-Fi üzerinden, internet gerekmez).
 * Her hatırlatma /r/<id> yolunda bir DataItem. Saat telefondan uzaktayken yapılan değişiklikler
 * yerelde kalır, bağlantı gelince Data Layer kendiliğinden iletir.
 */
object Sync {
    const val PREFIX = "/r/"
    const val CAP_PHONE = "b12_phone"
    const val CAP_WATCH = "b12_watch"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun publish(ctx: Context, r: Reminder) {
        val app = ctx.applicationContext
        io.execute {
            try {
                val req = PutDataMapRequest.create(PREFIX + r.id)
                req.dataMap.putString("json", r.toJson().toString())
                req.dataMap.putLong("t", r.updatedAt)
                Tasks.await(Wearable.getDataClient(app).putDataItem(req.asPutDataRequest().setUrgent()), 20, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        }
    }

    /** Açılışta: Data Layer'daki her şeyi al, yerelde daha yeni olanları geri gönder. */
    fun pullAll(ctx: Context, done: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        io.execute {
            try {
                val best = HashMap<String, Long>()
                val buf = Tasks.await(Wearable.getDataClient(app).dataItems, 20, TimeUnit.SECONDS)
                try {
                    for (item in buf) {
                        if (item.uri.path?.startsWith(PREFIX) != true) continue
                        val r = parse(DataMapItem.fromDataItem(item).dataMap.getString("json")) ?: continue
                        best[r.id] = maxOf(best[r.id] ?: 0L, r.updatedAt)
                        Store.merge(app, r)
                    }
                } finally {
                    buf.release()
                }
                for (r in Store.all(app)) if ((best[r.id] ?: -1L) < r.updatedAt) publish(app, r)
            } catch (_: Exception) {
            }
            done?.let { main.post(it) }
        }
    }

    /** Son bakışta öbür cihazda B12 yüklü müydü (varsayılan: evet). */
    fun peerHasApp(ctx: Context) = ctx.getSharedPreferences("b12", Context.MODE_PRIVATE).getBoolean("peerApp", true)

    fun parse(json: String?): Reminder? = try {
        json?.let { Reminder.fromJson(JSONObject(it)) }
    } catch (_: Exception) {
        null
    }

    /** Bildirim eylemini (Yaptım/Ertele/Başla) bağlı cihazlara da ilet. */
    fun send(ctx: Context, path: String, data: String, done: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        io.execute {
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(app).connectedNodes, 10, TimeUnit.SECONDS)
                for (n in nodes) {
                    try {
                        Tasks.await(Wearable.getMessageClient(app).sendMessage(n.id, path, data.toByteArray()), 10, TimeUnit.SECONDS)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
            done?.invoke()
        }
    }

    /**
     * Öbür cihazın durumu: (bağlı cihaz adı ya da null, uygulama orada yüklü mü).
     * [capability] öbür taraftaki uygulamanın yayınladığı ad (telefondan bakınca CAP_WATCH).
     */
    fun peer(ctx: Context, capability: String, cb: (String?, Boolean) -> Unit) {
        val app = ctx.applicationContext
        io.execute {
            var name: String? = null
            var installed = false
            try {
                val nodes = Tasks.await(Wearable.getNodeClient(app).connectedNodes, 10, TimeUnit.SECONDS)
                name = nodes.firstOrNull()?.displayName
                val cap = Tasks.await(
                    Wearable.getCapabilityClient(app).getCapability(capability, CapabilityClient.FILTER_REACHABLE),
                    10, TimeUnit.SECONDS,
                )
                installed = cap.nodes.isNotEmpty()
                app.getSharedPreferences("b12", Context.MODE_PRIVATE).edit().putBoolean("peerApp", installed).apply()
                if (installed) name = cap.nodes.first().displayName
            } catch (_: Exception) {
            }
            main.post { cb(name, installed) }
        }
    }
}

class SyncService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.type != DataEvent.TYPE_CHANGED) continue
            val item = e.dataItem
            if (item.uri.path?.startsWith(Sync.PREFIX) != true) continue
            Sync.parse(DataMapItem.fromDataItem(item).dataMap.getString("json"))?.let { Store.merge(this, it) }
        }
    }

    override fun onMessageReceived(m: MessageEvent) {
        val parts = String(m.data).split("|")
        val id = parts[0]
        val at = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        when (m.path) {
            "/n/done" -> {
                Notifier.cancel(this, id)
                Alarms.cancelLater(this, id)
            }
            "/n/snooze" -> {
                Notifier.cancel(this, id)
                Alarms.later(this, id, at, Alarms.SNOOZED)
            }
            "/n/start" -> {
                val r = Store.get(this, id) ?: return
                Notifier.started(this, r, at)
                Alarms.later(this, id, at, Alarms.ENDED)
            }
        }
    }
}
