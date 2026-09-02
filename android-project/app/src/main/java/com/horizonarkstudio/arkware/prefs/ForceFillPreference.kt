package com.horizonarkstudio.arkware.prefs

import android.content.Context
import com.horizonarkstudio.arkware.logging.ArkLogger

/**
 * Persists the stretch-to-fill toggle across fullscreen exit/re-entry
 * and app restarts. Thin wrapper so `SharedPreferences` details (the
 * file name, the key) live in exactly one place.
 */
class ForceFillPreference(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isEnabled: Boolean = try {
        prefs.getBoolean(KEY_FORCE_FILL, false)
    } catch (t: Throwable) {
        ArkLogger.e(COMPONENT, "Failed to read force-fill preference; defaulting to false", t)
        false
    }
        private set

    fun toggle(): Boolean {
        val next = !isEnabled
        set(next)
        return next
    }

    fun set(enabled: Boolean) {
        isEnabled = enabled
        try {
            prefs.edit().putBoolean(KEY_FORCE_FILL, enabled).apply()
        } catch (t: Throwable) {
            ArkLogger.e(COMPONENT, "Failed to persist force-fill preference", t)
        } finally {
            ArkLogger.d(COMPONENT, "force-fill preference now $enabled")
        }
    }

    private companion object {
        const val COMPONENT = "ForceFillPreference"
        const val PREFS_NAME = "arkware_prefs"
        const val KEY_FORCE_FILL = "force_fill_enabled"
    }
}
