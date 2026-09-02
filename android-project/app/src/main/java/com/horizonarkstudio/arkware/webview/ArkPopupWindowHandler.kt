package com.horizonarkstudio.arkware.webview

import android.app.Dialog
import android.net.Uri
import android.os.Message
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.horizonarkstudio.arkware.config.SpaConfig
import com.horizonarkstudio.arkware.logging.ArkLogger

/**
 * Handles WebView "popup" windows opened via the SPA's own
 * `window.open()` -- most commonly a third-party sign-in flow
 * (GitHub/Microsoft/Google OAuth). A real browser opens these as a
 * second window/tab; a bare Android WebView drops them silently
 * unless WebChromeClient.onCreateWindow() is both enabled (see
 * ArkWebViewFactory.configureSettings's javaScriptCanOpenWindowsAutomatically
 * / setSupportMultipleWindows) and actually handled, which is what
 * this class does -- without it, a "Sign in" button that opens a
 * popup looks like a dead click with nothing in Logcat to explain
 * why.
 *
 * The popup is hosted in a transient full-screen [Dialog], not the
 * app's own rootLayout, so the user can complete the flow (password
 * entry, a 2FA prompt) without it fighting the primary WebView for
 * layout or navigation state -- same reasoning as
 * FullscreenVideoController hosting fullscreen video outside the
 * normal view tree.
 *
 * Closing the popup is handled two ways, because OAuth flows use
 * both in the wild:
 *  - the popup calls `window.close()` itself once the flow finishes
 *    (WebChromeClient.onCloseWindow -> [closePopupWindow])
 *  - the popup instead redirects back to the opener's own origin
 *    (vscode.dev, or whichever SPA this build targets) rather than
 *    closing explicitly -- detected here by comparing hosts on every
 *    page load inside the popup
 */
class ArkPopupWindowHandler(private val activity: AppCompatActivity) {

    private var popupDialog: Dialog? = null
    private val openerHost: String? = Uri.parse(SpaConfig.targetUrl).host

    /**
     * WebChromeClient.onCreateWindow() calls this and should return
     * its result directly. [resultMsg] carries the WebViewTransport
     * the new WebView has to be attached to -- see
     * https://developer.android.com/reference/android/webkit/WebView#requestFocusNodeHref(android.os.Message)
     * for the general Message/Transport shape this follows.
     */
    fun createPopupWindow(resultMsg: Message): Boolean {
        var handled = false
        ArkLogger.track(COMPONENT, "createPopupWindow") {
            dismissExistingPopup()

            val popupWebView = WebView(activity).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = settings.userAgentString?.replace("; wv", "")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        if (url != null && Uri.parse(url).host == openerHost) {
                            // Landed back on the opener's own origin --
                            // treat that the same as an explicit
                            // window.close() from the popup's JS.
                            dismissExistingPopup()
                        }
                    }
                }
            }

            val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                setContentView(
                    popupWebView,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                setCancelable(true)
                setOnDismissListener {
                    popupWebView.destroy()
                    popupDialog = null
                }
            }
            popupDialog = dialog
            dialog.show()

            val transport = resultMsg.obj as? WebView.WebViewTransport
            if (transport != null) {
                transport.webView = popupWebView
                resultMsg.sendToTarget()
                handled = true
            } else {
                // Shouldn't happen -- resultMsg.obj is always a
                // WebViewTransport for onCreateWindow -- but fail
                // closed (dismiss, report unhandled) rather than leave
                // an empty dialog on screen if some WebView
                // implementation ever hands us something else.
                ArkLogger.e(COMPONENT, "createPopupWindow: resultMsg.obj was not a WebViewTransport", null)
                dismissExistingPopup()
            }
        }
        return handled
    }

    /** WebChromeClient.onCloseWindow() should call this directly. */
    fun closePopupWindow() {
        ArkLogger.track(COMPONENT, "closePopupWindow") {
            dismissExistingPopup()
        }
    }

    private fun dismissExistingPopup() {
        popupDialog?.dismiss()
        popupDialog = null
    }

    companion object {
        private const val COMPONENT = "ArkPopupWindowHandler"
    }
}
