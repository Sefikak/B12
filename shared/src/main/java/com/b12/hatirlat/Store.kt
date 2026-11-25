package com.b12.hatirlat

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import java.util.concurrent.CopyOnWriteArraySet

/** Cihazdaki hatırlatma listesi. Telefon da saat de kendi kopyasını tutar; Sync ikisini eşitler. */
object Store {
    private const val PREFS = "b12"
    private const val KEY = "reminders"
    private val lock = Any()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    /** Uygulamaya özel ek yenileme (telefonda widget, saatte Tile ve kadran). Application.onCreate'te atanır. */
    @Volatile var onChangeHook: ((Context) -> Unit)? = null
    @Volatile private var app: Context? = null

    private fun prefs(ctx: Context): android.content.SharedPreferences {
        app = ctx.applicationContext
        return ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun read(ctx: Context): MutableList<Reminder> {
        val s = prefs(ctx).getString(KEY, null) ?: return mutableListOf()
        return try {
            val a = JSONArray(s)
            MutableList(a.length()) { Reminder.fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun write(ctx: Context, list: List<Reminder>) {
        prefs(ctx).edit().putString(KEY, JSONArray(list.map { it.toJson() }).toString()).commit()
    }

    fun all(ctx: Context): List<Reminder> = synchronized(lock) { read(ctx) }

    fun visible(ctx: Context): List<Reminder> = all(ctx).filter { !it.deleted }
        .sortedWith(compareBy<Reminder>({ it.times.minOrNull() ?: 9999 }, { it.title.lowercase() }))

    fun get(ctx: Context, id: String) = all(ctx).firstOrNull { it.id == id }

    /** Bu cihazda yapılan değişiklik: kaydeder, alarmı kurar, öbür cihaza gönderir. */
    fun save(ctx: Context, r: Reminder): Reminder {
        val saved = synchronized(lock) {
            val list = read(ctx)
            val i = list.indexOfFirst { it.id == r.id }
            val t = maxOf(System.currentTimeMillis(), (if (i >= 0) list[i].updatedAt else 0L) + 1)
            val n = r.copy(updatedAt = t)
            if (i >= 0) list[i] = n else list.add(n)
            write(ctx, list)
            n
        }
        Alarms.schedule(ctx, saved)
        if (saved.deleted || !saved.enabled) Notifier.cancel(ctx, saved.id)
        Sync.publish(ctx, saved)
        notifyChanged()
        return saved
    }

    fun delete(ctx: Context, id: String) {
        get(ctx, id)?.let { save(ctx, it.copy(deleted = true, enabled = false)) }
    }

    /** Öbür cihazdan gelen sürüm; yalnızca daha yeniyse alınır. */
    fun merge(ctx: Context, r: Reminder): Boolean {
        val applied = synchronized(lock) {
            val list = read(ctx)
            val i = list.indexOfFirst { it.id == r.id }
            if (i >= 0 && list[i].updatedAt >= r.updatedAt) {
                false
            } else {
                if (i >= 0) list[i] = r else list.add(r)
                write(ctx, list)
                true
            }
        }
        if (applied) {
            Alarms.schedule(ctx, r)
            if (r.deleted || !r.enabled) Notifier.cancel(ctx, r.id)
            notifyChanged()
        }
        return applied
    }

    fun notifyChanged() {
        main.post {
            listeners.forEach { it() }
            app?.let { a -> try { onChangeHook?.invoke(a) } catch (_: Exception) {} }
        }
    }
}
