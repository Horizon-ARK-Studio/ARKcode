package com.horizonarkstudio.arkware

import android.app.Application
import com.horizonarkstudio.arkware.logging.ArkLogger
import com.google.android.material.color.DynamicColors

/**
 * Enables Material You dynamic color (wallpaper-derived theming) on
 * Android 12+ (API 31+) devices, app-wide, and owns process-wide
 * startup that has to happen before any Activity might run -- most
 * importantly [ArkLogger.init], so the failure log is live before any
 * other component could possibly need it.
 *
 * [DynamicColors.applyToActivitiesIfAvailable] is a no-op below API
 * 31, so this is safe across this app's full minSdk range -- devices
 * that can't do dynamic color just keep the branded fallback palette
 * defined in themes.xml / values-night/themes.xml. In practice this
 * only affects the splash background and system bars, since the
 * WebView content itself is the target SPA's own theming, not this
 * app's.
 *
 * A background poller mirroring the target SPA's own in-page
 * notifications (ARKtube's `NotificationSyncWorker`, which read the
 * user's YouTube notification inbox) is deliberately not carried over
 * here -- it's YouTube-markup-specific, not a generic shell affordance,
 * and isn't part of ARKware v1's scope (see
 * docs/Foundational/ROADMAP.md's v1 "in scope" list at the repo root).
 */
class ArkwareApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Logger first, deliberately outside the track{} below: if
        // init() itself is what's failing, ArkLogger.init() already
        // falls back to Logcat-only on its own (see its own
        // doc comment) -- there's no failure log to report into yet.
        ArkLogger.init(this)

        ArkLogger.track(COMPONENT, "onCreate") {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }
    }

    private companion object {
        const val COMPONENT = "ArkwareApplication"
    }
}
