package com.example.snowfall

import android.content.Context

/**
 * Sozlamalar. Activity o'zgartiradi, WeatherView har kadrda o'qiydi,
 * shuning uchun o'zgarishlar qatlamda darhol ko'rinadi.
 */
object SnowConfig {
    const val MODE_SNOW = 0
    const val MODE_RAIN = 1
    const val MODE_AUTUMN = 2
    const val MODE_STORM = 3

    private const val PREFS = "snowfall_prefs"

    @Volatile var mode: Int = MODE_SNOW
    @Volatile var sizePct: Int = 100      // 50..250
    @Volatile var countPct: Int = 100     // 10..300
    @Volatile var speedPct: Int = 100     // 40..250
    @Volatile var windPct: Int = 0        // -100..100
    @Volatile var colorIdx: Int = 0       // 0 oq, 1 ko'k, 2 pushti, 3 oltin, 4 rang-barang
    @Volatile var clouds: Boolean = false
    @Volatile var pile: Boolean = true    // qor uyumi + qorbobo (faqat Qor rejimida)

    fun reset() {
        mode = MODE_SNOW
        sizePct = 100
        countPct = 100
        speedPct = 100
        windPct = 0
        colorIdx = 0
        clouds = false
        pile = true
    }

    fun load(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mode = p.getInt("mode", MODE_SNOW).coerceIn(0, 3)
        sizePct = p.getInt("sizePct", 100).coerceIn(50, 250)
        countPct = p.getInt("countPct", 100).coerceIn(10, 300)
        speedPct = p.getInt("speedPct", 100).coerceIn(40, 250)
        windPct = p.getInt("windPct", 0).coerceIn(-100, 100)
        colorIdx = p.getInt("colorIdx", 0).coerceIn(0, 4)
        clouds = p.getBoolean("clouds", false)
        pile = p.getBoolean("pile", true)
    }

    fun save(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("mode", mode)
            .putInt("sizePct", sizePct)
            .putInt("countPct", countPct)
            .putInt("speedPct", speedPct)
            .putInt("windPct", windPct)
            .putInt("colorIdx", colorIdx)
            .putBoolean("clouds", clouds)
            .putBoolean("pile", pile)
            .apply()
    }
}
