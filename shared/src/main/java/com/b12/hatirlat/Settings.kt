package com.b12.hatirlat

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/** Genel ayarlar (sessiz saatler). Telefon ve saat arasında eşitlenir (/s/settings). */
data class Settings(
    val quietOn: Boolean = true,
    val quietStart: Int = 23 * 60,
    val quietEnd: Int = 7 * 60,
    val updatedAt: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().put("quietOn", quietOn).put("quietStart", quietStart)
        .put("quietEnd", quietEnd).put("updatedAt", updatedAt)

    /** [t] anı sessiz saatlerin içinde mi? (gece yarısını aşan aralık desteklenir) */
    fun isQuiet(t: Long): Boolean {
        if (!quietOn || quietStart == quietEnd) return false
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (quietStart < quietEnd) m in quietStart until quietEnd else m >= quietStart || m < quietEnd
    }

    fun text() = if (quietOn) "${Reminder.hm(quietStart)} – ${Reminder.hm(quietEnd)}" else "Kapalı"

    companion object {
        private const val KEY = "settings"
        const val PATH = "/s/settings"

        fun fromJson(o: JSONObject) = Settings(
            o.optBoolean("quietOn", true), o.optInt("quietStart", 23 * 60), o.optInt("quietEnd", 7 * 60), o.optLong("updatedAt"),
        )

        fun get(ctx: Context): Settings = try {
            ctx.applicationContext.getSharedPreferences("b12", Context.MODE_PRIVATE).getString(KEY, null)
                ?.let { fromJson(JSONObject(it)) } ?: Settings()
        } catch (_: Exception) {
            Settings()
        }

        private fun write(ctx: Context, s: Settings) {
            ctx.applicationContext.getSharedPreferences("b12", Context.MODE_PRIVATE).edit().putString(KEY, s.toJson().toString()).commit()
        }

        /** Bu cihazda değişti: kaydet ve öbür cihaza gönder. */
        fun save(ctx: Context, s: Settings) {
            val n = s.copy(updatedAt = maxOf(System.currentTimeMillis(), get(ctx).updatedAt + 1))
            write(ctx, n)
            Sync.publishSettings(ctx, n)
            Store.notifyChanged()
        }

        fun merge(ctx: Context, s: Settings): Boolean {
            if (s.updatedAt <= get(ctx).updatedAt) return false
            write(ctx, s)
            Store.notifyChanged()
            return true
        }
    }
}
