package com.horizonarkstudio.arkware.theme

import android.view.Window
import androidx.core.view.WindowInsetsControllerCompat
import com.horizonarkstudio.arkware.logging.ArkLogger

/**
 * Paints the status/nav bars to match the color THEME_SYNC_JS found
 * YouTube actually rendering (falling back to a plain light/dark
 * swatch if that color couldn't be read), and flips the bar icons'
 * own appearance so they stay legible against it.
 *
 * [apply] itself also guards against redundant writes (skips if the
 * resolved color/darkness already match the last call) as
 * defense-in-depth: THEME_SYNC_JS's own `report()` now dedupes before
 * it ever calls the bridge (see that constant's doc comment and
 * BUG-0001 in docs/bugs-caught/), but this is a cheap second line of
 * defense against the same "reassert an already-current value" shape
 * from any future caller, the same instinct as `wasPlaying` in
 * MediaPlaybackService.updatePlaybackState().
 */
class StatusBarThemeApplier(private val window: Window) {

    private var lastAppliedColor: Int? = null
    private var lastAppliedIsDark: Boolean? = null

    fun apply(isDark: Boolean, cssBackground: String?) {
        ArkLogger.track(COMPONENT, "apply") {
            val barColor = CssColorParser.parse(cssBackground)
                ?: if (isDark) FALLBACK_DARK_COLOR else FALLBACK_LIGHT_COLOR

            if (barColor == lastAppliedColor && isDark == lastAppliedIsDark) {
                ArkLogger.d(COMPONENT, "apply: no-op, color/darkness unchanged")
                return@track
            }
            lastAppliedColor = barColor
            lastAppliedIsDark = isDark

            window.statusBarColor = barColor
            window.navigationBarColor = barColor

            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    private companion object {
        const val COMPONENT = "StatusBarThemeApplier"
        val FALLBACK_DARK_COLOR = 0xFF0F0F0F.toInt()
        val FALLBACK_LIGHT_COLOR = 0xFFFFFFFF.toInt()
    }
}
