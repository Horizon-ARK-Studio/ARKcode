package com.horizonarkstudio.arkware.webview.bridge

import android.webkit.JavascriptInterface

/**
 * Receives client-generated (`blob:` URL) file downloads from
 * BLOB_DOWNLOAD_INTERCEPT_JS as `window.ArkDownload`.
 *
 * This exists because Android WebView's `DownloadListener` only ever
 * fires for genuine http(s) URL downloads (see
 * MainActivity.setupDownloadListener()'s own doc) -- a `blob:` URL
 * (a file built entirely in page JS: Settings Sync export, a
 * generated `.vsix`, a "Save As" fallback for an SPA with no File
 * System Access API to write through) never reaches
 * `DownloadListener` at all; the request just silently vanishes.
 * BLOB_DOWNLOAD_INTERCEPT_JS catches the anchor click before the
 * WebView tries (and fails) to navigate to the `blob:` URL itself,
 * reads the Blob's bytes as base64 in page JS -- the only place
 * those bytes are reachable at all, native code has no way to
 * dereference a `blob:` URL on its own -- and hands them here to
 * actually be written to disk.
 */
class DownloadBridge(
    private val listener: BlobDownloadListener
) : ArkJsBridge("DownloadBridge") {

    @JavascriptInterface
    fun saveBlobFile(base64Data: String, filename: String, mimeType: String) {
        safeCall("saveBlobFile") {
            listener.onBlobDownload(base64Data, filename, mimeType)
        }
    }
}
