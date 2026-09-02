package com.horizonarkstudio.arkware.webview

import android.content.Context
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.horizonarkstudio.arkware.config.SpaConfig
import com.horizonarkstudio.arkware.logging.ArkLogger
import com.horizonarkstudio.arkware.webview.bridge.BlobDownloadListener
import com.horizonarkstudio.arkware.webview.bridge.DownloadBridge
import com.horizonarkstudio.arkware.webview.bridge.FullscreenVideoSizeListener
import com.horizonarkstudio.arkware.webview.bridge.MediaPlaybackBridge
import com.horizonarkstudio.arkware.webview.bridge.MediaPlaybackListener
import com.horizonarkstudio.arkware.webview.bridge.OrientationBridge
import com.horizonarkstudio.arkware.webview.bridge.ThemeBridge
import com.horizonarkstudio.arkware.webview.bridge.ThemeChangeListener

/**
 * GoF Factory: the single place that knows how to assemble a
 * fully-configured ARKware WebView (settings, user agent, the four
 * JS bridges, and script injection on page load). Callers hand in
 * listeners and native callbacks; they never touch WebSettings
 * directly.
 *
 * The target SPA's own URL is deliberately not read here -- that's
 * [SpaConfig.targetUrl], loaded by whoever owns the WebView (see
 * MainActivity), so this factory stays about *how* a WebView gets
 * configured, not *which site* it eventually loads.
 */
object ArkWebViewFactory {

    private const val COMPONENT = "ArkWebViewFactory"

    fun create(
        context: Context,
        themeListener: ThemeChangeListener,
        orientationListener: FullscreenVideoSizeListener,
        mediaPlaybackListener: MediaPlaybackListener,
        blobDownloadListener: BlobDownloadListener,
        webChromeClient: WebChromeClient
    ): WebView = ArkLogger.track(COMPONENT, "create") {
        val webView = WebView(context)
        try {
            configureSettings(webView)
            attachBridges(webView, themeListener, orientationListener, mediaPlaybackListener, blobDownloadListener)
            webView.webViewClient = buildWebViewClient()
            webView.webChromeClient = webChromeClient
        } catch (t: Throwable) {
            ArkLogger.e(COMPONENT, "Failed while configuring WebView", t)
            throw t
        } finally {
            ArkLogger.d(COMPONENT, "WebView configuration pass finished")
        }
        webView
    }

    private fun configureSettings(webView: WebView) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        // Without this, WebView ignores the page's own viewport meta
        // tag and a mobile-optimized SPA renders its desktop-style
        // layout instead of the phone layout it would serve a real
        // mobile browser (this is what m.youtube.com needed to render
        // correctly, and generalizes to any responsive SPA).
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        // Identify as a mobile browser so a responsive SPA serves its
        // actual mobile layout rather than a desktop layout squeezed
        // into a phone-sized WebView.
        webView.settings.userAgentString = webView.settings.userAgentString?.replace("; wv", "")
        // Without these two, `window.open()` (the mechanism most
        // third-party sign-in flows -- GitHub/Microsoft/Google OAuth --
        // use to pop a second window) is a silent no-op: WebChromeClient
        // .onCreateWindow() is simply never invoked at all. See
        // ArkPopupWindowHandler, which is what actually receives that
        // callback once it's enabled to fire.
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.setSupportMultipleWindows(true)
    }

    private fun attachBridges(
        webView: WebView,
        themeListener: ThemeChangeListener,
        orientationListener: FullscreenVideoSizeListener,
        mediaPlaybackListener: MediaPlaybackListener,
        blobDownloadListener: BlobDownloadListener
    ) {
        webView.addJavascriptInterface(ThemeBridge(themeListener), "ArkTheme")
        webView.addJavascriptInterface(OrientationBridge(orientationListener), "ArkOrientation")
        webView.addJavascriptInterface(MediaPlaybackBridge(mediaPlaybackListener), "ArkMediaPlayback")
        webView.addJavascriptInterface(DownloadBridge(blobDownloadListener), "ArkDownload")
    }

    private fun buildWebViewClient(): WebViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            ArkLogger.track(COMPONENT, "onPageFinished:$url") {
                view.evaluateJavascript(ArkScripts.VIDEO_SIZE_REPORT_JS, null)
                view.evaluateJavascript(ArkScripts.THEME_SYNC_JS, null)
                view.evaluateJavascript(
                    ArkScripts.nagHideJs(SpaConfig.nagHideSelectors, SpaConfig.nagHideTextMatches), null
                )
                view.evaluateJavascript(ArkScripts.mediaSessionJs(SpaConfig.displayName), null)
                view.evaluateJavascript(ArkScripts.BLOB_DOWNLOAD_INTERCEPT_JS, null)
            }
        }
    }
}
