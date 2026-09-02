package com.horizonarkstudio.arkware.webview.bridge

import android.webkit.JavascriptInterface

/**
 * Receives play/pause/seek state and title/artwork from
 * MEDIA_SESSION_JS as `window.ArkMediaPlayback`. See
 * MediaSessionCoordinator for what happens downstream of this
 * (MediaSessionCompat, the notification, audio focus).
 */
class MediaPlaybackBridge(
    private val listener: MediaPlaybackListener
) : ArkJsBridge("MediaPlaybackBridge") {

    @JavascriptInterface
    fun onPlaybackState(isPlaying: Boolean, positionMs: Long, playbackRate: Float) {
        safeCall("onPlaybackState") {
            listener.onPlaybackState(isPlaying, positionMs, playbackRate)
        }
    }

    @JavascriptInterface
    fun onMediaInfo(title: String?, durationMs: Long, artworkUrl: String?) {
        safeCall("onMediaInfo") {
            listener.onMediaInfo(title, durationMs, artworkUrl)
        }
    }
}
