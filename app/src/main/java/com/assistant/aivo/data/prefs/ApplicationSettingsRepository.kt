package com.assistant.aivo.data.prefs

import android.content.Context

class ApplicationSettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("aivo_settings", Context.MODE_PRIVATE)

    fun isAppEnabled(packageName: String): Boolean {
        return prefs.getBoolean("enabled_$packageName", true)
    }

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled_$packageName", enabled).apply()
    }
}
