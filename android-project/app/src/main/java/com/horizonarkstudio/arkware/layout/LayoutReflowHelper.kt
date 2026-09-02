package com.horizonarkstudio.arkware.layout

import android.webkit.WebView
import com.horizonarkstudio.arkware.logging.ArkLogger
import com.horizonarkstudio.arkware.webview.ArkScripts

/**
 * Forces YouTube's own page JS to re-sync off-screen list items (most
 * visibly the "Up next"/related-videos row) to the WebView's real
 * width after a rotation -- Chromium's own layout reflows correctly
 * on its own, but page JS listening specifically for `resize`/
 * `orientationchange` DOM events doesn't reliably get told to
 * re-measure just because the WebView's own View was resized by the
 * framework. See the two-part fix in [reflow]: synthetic DOM events,
 * plus a 1px scroll nudge (the same trigger manual scrolling causes).
 *
 * Retried at a few short delays since rotation/inset settling and the
 * WebView's own internal resize don't all necessarily land in the
 * same frame.
 */
class LayoutReflowHelper(private val webView: WebView) {

    fun reflow(isFullscreenActive: () -> Boolean) {
        // No-op during fullscreen video: the fullscreen controller
        // already owns rotation while it's showing, and scrolling the
        // WebView underneath it would be pointless.
        if (isFullscreenActive()) return
        for (delayMs in REFLOW_RETRY_DELAYS_MS) {
            webView.postDelayed({
                ArkLogger.track(COMPONENT, "reflow@${delayMs}ms") {
                    if (isFullscreenActive()) return@track
                    webView.evaluateJavascript(ArkScripts.FORCE_REFLOW_JS, null)
                    webView.scrollBy(0, 1)
                    webView.scrollBy(0, -1)
                }
            }, delayMs)
        }
    }

    private companion object {
        const val COMPONENT = "LayoutReflowHelper"
        val REFLOW_RETRY_DELAYS_MS = longArrayOf(0L, 150L, 400L)
    }
}
