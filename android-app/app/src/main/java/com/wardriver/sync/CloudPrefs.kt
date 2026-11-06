package com.wardriver.sync

import android.content.Context
import java.util.UUID





class CloudPrefs(context: Context) : Watermark {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)


    var autoEnabled: Boolean
        get() = prefs.getBoolean("auto_enabled", false)
        set(v) = prefs.edit().putBoolean("auto_enabled", v).apply()


    var cloudEnabled: Boolean
        get() = prefs.getBoolean("cloud_enabled", false)
        set(v) = prefs.edit().putBoolean("cloud_enabled", v).apply()

    var intervalMin: Int
        get() = prefs.getInt("interval_min", DEFAULT_INTERVAL_MIN).coerceIn(1, 24 * 60)
        set(v) = prefs.edit().putInt("interval_min", v).apply()

    var url: String
        get() = prefs.getString("url", "").orEmpty()
        set(v) = prefs.edit().putString("url", v.trim()).apply()


    var consentAccepted: Boolean
        get() = prefs.getBoolean("consent", false)
        set(v) = prefs.edit().putBoolean("consent", v).apply()

    val hasToken: Boolean get() = !token.isNullOrEmpty()


    var token: String?
        get() = SecretBox.open(prefs.getString("token_sealed", null))
        set(v) {
            prefs.edit().apply { if (v.isNullOrEmpty()) remove("token_sealed") else putString("token_sealed", SecretBox.seal(v)) }.apply()
        }

    override var value: Long
        get() = prefs.getLong("uploaded_until_ms", 0L)
        set(v) = prefs.edit().putLong("uploaded_until_ms", v).apply()

    fun resetWatermark() { value = 0L }

    var lastCycleMs: Long
        get() = prefs.getLong("last_cycle_ms", 0L)
        private set(v) = prefs.edit().putLong("last_cycle_ms", v).apply()

    var lastCycleSummary: String
        get() = prefs.getString("last_cycle_summary", "").orEmpty()
        private set(v) = prefs.edit().putString("last_cycle_summary", v).apply()

    fun recordCycle(nowMs: Long, summary: String) { lastCycleMs = nowMs; lastCycleSummary = summary }


    val deviceId: String
        get() = prefs.getString("device_id", null) ?: ("android-" + UUID.randomUUID().toString().take(8)).also {
            prefs.edit().putString("device_id", it).apply()
        }

    fun endpoint(): CloudUrl.Endpoint? = (CloudUrl.parse(url) as? CloudUrl.Parsed.Ok)?.endpoint


    val cloudReady: Boolean get() = cloudEnabled && consentAccepted && endpoint() != null && hasToken

    companion object {
        const val FILE = "cloud"
        const val DEFAULT_INTERVAL_MIN = 10
        val INTERVAL_OPTIONS = listOf(5, 10, 15, 30, 60)
    }
}
