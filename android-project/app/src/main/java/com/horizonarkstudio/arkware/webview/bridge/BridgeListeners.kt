package com.horizonarkstudio.arkware.webview.bridge

/** Reported by [ThemeBridge] when THEME_SYNC_JS detects a page background/theme change. */
fun interface ThemeChangeListener {
    fun onThemeChanged(isDark: Boolean, cssBackground: String?)
}

/** Reported by [OrientationBridge] when VIDEO_SIZE_REPORT_JS reads a new intrinsic video size. */
fun interface FullscreenVideoSizeListener {
    fun onFullscreenVideoSize(width: Int, height: Int)
}

/** Reported by [MediaPlaybackBridge] as MEDIA_SESSION_JS watches the page's <video> element. */
interface MediaPlaybackListener {
    fun onPlaybackState(isPlaying: Boolean, positionMs: Long, playbackRate: Float)
    fun onMediaInfo(title: String?, durationMs: Long, artworkUrl: String?)
}

/**
 * Reported by [DownloadBridge] when BLOB_DOWNLOAD_INTERCEPT_JS catches
 * a client-generated `blob:` URL download that a plain
 * `WebView.DownloadListener` would never see (that only fires for
 * genuine http(s) URLs). [base64Data] is the Blob's bytes, already
 * base64-encoded in page JS since that's the only place a `blob:`
 * URL can be dereferenced at all.
 */
fun interface BlobDownloadListener {
    fun onBlobDownload(base64Data: String, filename: String, mimeType: String)
}
