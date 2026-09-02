package com.horizonarkstudio.arkware.webview

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.appcompat.app.AppCompatActivity
import com.horizonarkstudio.arkware.logging.ArkLogger

/**
 * Handles WebView `<input type="file">` chooser requests -- including
 * whatever fallback an SPA reaches for when it wants local file
 * access but the platform doesn't offer the File System Access API
 * (`showOpenFilePicker`/`showDirectoryPicker`). Android WebView never
 * implements that API at all, so vscode.dev's own fallback UI for
 * "Open File" on Android is exactly this. A bare WebView has no
 * default handling for `WebChromeClient.onShowFileChooser()` --
 * without it, clicking that control is a silent dead click, the same
 * failure shape `window.open()` had before ArkPopupWindowHandler
 * existed.
 *
 * `Activity#startActivityForResult` is used deliberately here (over
 * the newer `ActivityResultLauncher` API) to avoid taking on a new
 * dependency surface just for this one call site -- same "known
 * deprecated but simplest, guaranteed-available API" tradeoff
 * MainActivity.onBackPressed() already makes elsewhere in this app.
 */
@Suppress("DEPRECATION")
class ArkFileChooserHandler(private val activity: AppCompatActivity) {

    private var pendingCallback: ValueCallback<Array<Uri>>? = null

    /**
     * WebChromeClient.onShowFileChooser() calls this and should
     * return its result directly. Only one chooser can be pending at
     * a time -- a second call while one is already in flight resolves
     * the SPA's stale first request with no selection, matching how a
     * real browser tab behaves if a second file input fires before
     * the first dialog resolves.
     */
    fun showFileChooser(
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: WebChromeClient.FileChooserParams
    ): Boolean {
        var handled = false
        ArkLogger.track(COMPONENT, "showFileChooser") {
            pendingCallback?.onReceiveValue(null)
            pendingCallback = filePathCallback

            val intent = fileChooserParams.createIntent().apply {
                if (fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
            }
            try {
                activity.startActivityForResult(intent, REQUEST_CODE_FILE_CHOOSER)
                handled = true
            } catch (t: Throwable) {
                // No activity on-device can handle the chooser intent
                // at all (extremely unlikely, but every other bridge
                // in this app fails closed and logs rather than
                // leaving the SPA's promise hanging forever).
                ArkLogger.e(COMPONENT, "showFileChooser: no activity to handle chooser intent", t)
                pendingCallback = null
                filePathCallback.onReceiveValue(null)
            }
        }
        return handled
    }

    /** MainActivity.onActivityResult() should call this for [REQUEST_CODE_FILE_CHOOSER]. */
    fun onFileChooserResult(resultCode: Int, data: Intent?) {
        ArkLogger.track(COMPONENT, "onFileChooserResult") {
            val callback = pendingCallback
            pendingCallback = null
            if (callback == null) return@track

            val results = if (resultCode == Activity.RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else {
                null
            }
            callback.onReceiveValue(results)
        }
    }

    companion object {
        private const val COMPONENT = "ArkFileChooserHandler"
        const val REQUEST_CODE_FILE_CHOOSER = 2001
    }
}
