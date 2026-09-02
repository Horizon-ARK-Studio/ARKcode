package com.horizonarkstudio.arkware.media

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.os.IBinder
import android.webkit.WebView
import androidx.core.content.ContextCompat
import com.horizonarkstudio.arkware.MediaPlaybackService
import com.horizonarkstudio.arkware.logging.ArkLogger
import com.horizonarkstudio.arkware.webview.ArkScripts
import com.horizonarkstudio.arkware.webview.bridge.MediaPlaybackListener

/**
 * Owns the MainActivity side of the media-session integration:
 * binding/unbinding [MediaPlaybackService], translating its transport
 * callbacks (play/pause/seek/skip from the lock screen, a Bluetooth
 * headset, etc.) into JS calls against the page's real `<video>`
 * element, and loading notification artwork off the main thread.
 *
 * [MediaPlaybackService] itself still owns the actual
 * MediaSessionCompat/notification/audio-focus handling -- this class
 * is purely the glue between it and the WebView.
 */
class MediaSessionCoordinator(
    private val activity: Activity,
    private val webViewProvider: () -> WebView
) : MediaPlaybackListener {

    private var mediaService: MediaPlaybackService? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            ArkLogger.track(COMPONENT, "onServiceConnected") {
                val service = (binder as MediaPlaybackService.LocalBinder).service
                mediaService = service
                service.setCommandListener(commandListener)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // Only called on an actual crash of the service's process,
            // not our own unbindService() -- null it out regardless so
            // a stray late callback can't reach a dead binder.
            ArkLogger.w(COMPONENT, "onServiceDisconnected (service process likely crashed)")
            mediaService = null
        }
    }

    private val commandListener = object : MediaPlaybackService.CommandListener {
        override fun onPlayCommand() = runJs(ArkScripts.MEDIA_CONTROL_PLAY_JS, "onPlayCommand")
        override fun onPauseCommand() = runJs(ArkScripts.MEDIA_CONTROL_PAUSE_JS, "onPauseCommand")
        override fun onSeekToCommand(positionMs: Long) =
            runJs(ArkScripts.mediaControlSeekJs(positionMs), "onSeekToCommand")

        override fun onFastForwardCommand() =
            runJs(ArkScripts.mediaControlSkipJs(SEEK_STEP_SECONDS), "onFastForwardCommand")

        override fun onRewindCommand() =
            runJs(ArkScripts.mediaControlSkipJs(-SEEK_STEP_SECONDS), "onRewindCommand")
    }

    fun bind() {
        ArkLogger.track(COMPONENT, "bind") {
            activity.bindService(
                Intent(activity, MediaPlaybackService::class.java), serviceConnection, Context.BIND_AUTO_CREATE
            )
            bound = true
        }
    }

    fun unbind() {
        ArkLogger.track(COMPONENT, "unbind") {
            if (bound) {
                mediaService?.setCommandListener(null)
                activity.unbindService(serviceConnection)
                bound = false
            }
            activity.stopService(Intent(activity, MediaPlaybackService::class.java))
        }
    }

    // The bridge invokes these on the WebView's JS thread; hop to the
    // UI thread before touching mediaService/starting the foreground
    // service, same as the original bridge-side runOnUiThread did.
    override fun onPlaybackState(isPlaying: Boolean, positionMs: Long, playbackRate: Float) {
        activity.runOnUiThread {
            ArkLogger.track(COMPONENT, "onPlaybackState") {
                if (isPlaying) {
                    // Promotes the already-bound service into the
                    // foreground -- and therefore posts the notification --
                    // the first time real playback is reported. Safe to
                    // call again on every subsequent play too.
                    ContextCompat.startForegroundService(activity, Intent(activity, MediaPlaybackService::class.java))
                }
                mediaService?.updatePlaybackState(isPlaying, positionMs, playbackRate)
            }
        }
    }

    override fun onMediaInfo(title: String?, durationMs: Long, artworkUrl: String?) {
        activity.runOnUiThread { loadArtworkAndApplyMetadata(title, durationMs, artworkUrl) }
    }

    private fun runJs(script: String, operation: String) {
        ArkLogger.track(COMPONENT, operation) {
            webViewProvider().evaluateJavascript(script, null)
        }
    }

    private fun loadArtworkAndApplyMetadata(title: String?, durationMs: Long, artworkUrl: String?) {
        val service = mediaService ?: return
        if (artworkUrl.isNullOrBlank()) {
            service.updateMetadata(title, durationMs, null)
            return
        }
        Thread {
            ArkLogger.track(COMPONENT, "loadArtwork") {
                val artwork = try {
                    java.net.URL(artworkUrl).openStream().use { BitmapFactory.decodeStream(it) }
                } catch (t: Throwable) {
                    // Deliberately tolerant: artwork is a nice-to-have
                    // for the notification, not something that should
                    // block title/duration from reaching the session.
                    ArkLogger.w(COMPONENT, "Artwork load failed for $artworkUrl", t)
                    null
                }
                activity.runOnUiThread { mediaService?.updateMetadata(title, durationMs, artwork) }
            }
        }.start()
    }

    private companion object {
        const val COMPONENT = "MediaSessionCoordinator"
        const val SEEK_STEP_SECONDS = 10
    }
}
