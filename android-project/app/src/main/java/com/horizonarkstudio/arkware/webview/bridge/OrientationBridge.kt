package com.horizonarkstudio.arkware.webview.bridge

import android.webkit.JavascriptInterface

/**
 * Receives the fullscreen stream's intrinsic width/height from
 * VIDEO_SIZE_REPORT_JS as `window.ArkOrientation`. This is the
 * one piece of the fullscreen-crop/orientation feature that genuinely
 * has to come from JS (video.videoWidth/videoHeight is DOM-only);
 * everything downstream of it (rotation lock, the actual zoom crop)
 * is native -- see FullscreenVideoController.
 */
class OrientationBridge(
    private val listener: FullscreenVideoSizeListener
) : ArkJsBridge("OrientationBridge") {

    @JavascriptInterface
    fun onFullscreenVideoSize(width: Int, height: Int) {
        safeCall("onFullscreenVideoSize") {
            listener.onFullscreenVideoSize(width, height)
        }
    }
}
