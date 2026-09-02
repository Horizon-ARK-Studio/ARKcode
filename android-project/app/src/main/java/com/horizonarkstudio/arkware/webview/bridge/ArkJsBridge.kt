package com.horizonarkstudio.arkware.webview.bridge

import com.horizonarkstudio.arkware.logging.ArkLogger

/**
 * Common base for every `@JavascriptInterface`-annotated bridge class
 * exposed to page JS (ThemeBridge, OrientationBridge,
 * MediaPlaybackBridge).
 *
 * WebView invokes these methods on its own JS thread, not the UI
 * thread -- and, critically, an uncaught exception thrown from a
 * `@JavascriptInterface` method doesn't surface the way a normal
 * crash would; it can silently abort that one bridge call. [safeCall]
 * is what gives every bridge method uniform try/catch/finally
 * logging, so a failure inside a bridge shows up in Logcat and the
 * on-device failure log instead of just quietly not doing anything.
 * Subclasses stay one-liners: wrap the body in `safeCall("methodName") { ... }`.
 */
abstract class ArkJsBridge(private val componentName: String) {

    protected fun <T> safeCall(methodName: String, block: () -> T): T? {
        ArkLogger.d(componentName, "$methodName: invoked from page JS")
        return try {
            val result = block()
            ArkLogger.d(componentName, "$methodName: completed")
            result
        } catch (t: Throwable) {
            ArkLogger.e(componentName, "$methodName: threw", t)
            null
        } finally {
            ArkLogger.d(componentName, "$methodName: returning to page JS")
        }
    }
}
