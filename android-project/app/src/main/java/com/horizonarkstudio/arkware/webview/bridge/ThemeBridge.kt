package com.horizonarkstudio.arkware.webview.bridge

import android.webkit.JavascriptInterface

/**
 * Receives theme reports from THEME_SYNC_JS as `window.ArkTheme`.
 * Runs on the WebView's JS thread -- [listener] is responsible for
 * hopping back to the UI thread if it needs to (MainActivity's
 * implementation does, since applying a status bar color requires it).
 */
class ThemeBridge(private val listener: ThemeChangeListener) : ArkJsBridge("ThemeBridge") {

    @JavascriptInterface
    fun onThemeChanged(isDark: Boolean, cssBackground: String?) {
        safeCall("onThemeChanged") {
            listener.onThemeChanged(isDark, cssBackground)
        }
    }
}
