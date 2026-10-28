package com.neuralbridge.companion

import android.content.Context

/**
 * Shared app preferences. The master toggle key (`nb_enabled`) is read by the
 * activity, the accessibility service, the MCP foreground service and the boot
 * receiver, so it lives in one place.
 */
object AppPrefs {
    private const val PREFS_NAME = "neuralbridge_prefs"
    const val KEY_ENABLED = "nb_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
