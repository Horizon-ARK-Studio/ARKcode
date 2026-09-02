package com.horizonarkstudio.arkware

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.app.Service
import com.horizonarkstudio.arkware.config.SpaConfig
import com.horizonarkstudio.arkware.logging.ArkLogger
import com.horizonarkstudio.arkware.media.MediaNotificationFactory

/**
 * Hosts the app's one [MediaSessionCompat] and the media-style
 * notification/lock-screen transport controls that go with it, so
 * play/pause/seek reach the video playing inside MainActivity's
 * WebView from *outside* the app entirely: the notification shade,
 * the lock screen, a wired headset's inline remote, a Bluetooth
 * earbud/car-stereo's AVRCP buttons, a paired watch's media
 * complication -- anything the platform considers "a device that can
 * control the active media session", which is exactly what
 * MediaSessionCompat exists to broadcast to. None of those surfaces
 * talk to the WebView directly; they all go through this session.
 *
 * The notification itself -- what it looks like, its actions, its
 * channel -- is built by [MediaNotificationFactory] (GoF Factory),
 * not here; this class owns the session lifecycle only.
 * See docs/Foundational/CODE-STYLE.md Section 1 for why those are
 * kept as two separate reasons to change even though they're closely
 * related.
 *
 * This deliberately does not attempt real background/PiP-style
 * playback survival, a media queue/playlist, or Android Auto
 * browsing (all explicitly out of scope per MainActivity's class doc)
 * -- just correct transport control for whatever's playing in the
 * foreground WebView, for as long as this process is alive.
 *
 * Runs as a Service (rather than living directly in the Activity) for
 * two reasons: it's what lets Android show a MediaStyle notification
 * at all per the platform's own guidelines, and it keeps the session
 * -- and the becoming-noisy handling below -- alive across brief
 * Activity recreation instead of tearing down and rebuilding
 * transport control on every config change. It's bound
 * (see [LocalBinder]) as soon as MainActivity starts, but only
 * promoted into the *foreground* -- which is what actually posts the
 * notification -- the first time real playback is reported; see
 * MainActivity's MediaPlaybackBridge.onPlaybackState().
 *
 * MainActivity binds to this service and:
 *  - implements [CommandListener] to translate session callbacks
 *    (onPlay/onPause/onSeekTo/etc., however they arrived -- a tapped
 *    notification action, a Bluetooth AVRCP command, a wired headset
 *    button) into JS calls on the page's actual `<video>` element
 *  - calls [updatePlaybackState]/[updateMetadata] whenever
 *    MEDIA_SESSION_JS reports that same `<video>` element's own
 *    play/pause/seek/title changes, so the session -- and therefore
 *    the notification/lock screen/etc. -- stays truthful about what's
 *    actually happening on the page, not just a mirror of the last
 *    command sent to it.
 *
 * Every method here that crosses a platform lifecycle boundary --
 * onCreate/onStartCommand/onDestroy, and the two public update
 * methods JS-reported state flows through -- is wrapped with
 * [ArkLogger] try/catch/finally logging per
 * docs/Foundational/CODE-STYLE.md Section 3: none of these have a
 * caller that could meaningfully react to a rethrown exception, so a
 * failure here needs to be observable in the failure log rather than
 * silently dropped.
 */
class MediaPlaybackService : Service() {

    /** Implemented by MainActivity to actually carry out a transport command. */
    interface CommandListener {
        fun onPlayCommand()
        fun onPauseCommand()
        fun onSeekToCommand(positionMs: Long)
        fun onFastForwardCommand()
        fun onRewindCommand()
    }

    inner class LocalBinder : Binder() {
        val service: MediaPlaybackService get() = this@MediaPlaybackService
    }

    private val binder = LocalBinder()
    private lateinit var mediaSession: MediaSessionCompat
    private var commandListener: CommandListener? = null
    private var isPlaying = false

    // Deliberately NOT requesting native AudioFocusRequest here. This
    // service and WebView/Chromium's own internal media stack are two
    // independent audio-focus requesters for the *same physical stream*
    // (the page's <video> element) in the same process. Chromium already
    // requests focus for it and already pauses/resumes correctly on a
    // genuine external interruption (phone call, another app's playback)
    // per its own focus-loss handling -- that's not this app's job to
    // duplicate. When this service *also* held a request, its once-per-
    // play-start requestAudioFocus() evicted Chromium's own request,
    // Chromium (honoring focus-loss like any well-behaved player) paused
    // the real <video>, and the very next Play tap made Chromium
    // re-request focus and evict *this app's* request right back --
    // AUDIOFOCUS_LOSS_TRANSIENT landing on this app within milliseconds
    // of every tap, unconditionally re-pausing. Two correctly-behaved
    // focus requesters fighting over one stream. See
    // docs/bugs-caught/BUG-0004-audio-focus-ping-pong.md.
    //
    // Removing the request doesn't lose real interruption handling:
    // Chromium still owns and pauses the actual audio output on a
    // genuine AUDIOFOCUS_LOSS, and MediaSessionCompat/the notification
    // only need accurate *state*, which already arrives for free via the
    // JS bridge's real play/pause/ended events -- see updatePlaybackState()
    // below. ACTION_AUDIO_BECOMING_NOISY (headphone unplug) is unrelated
    // to audio focus and is still handled directly, below.

    // Pauses when the active audio route disappears (headphones
    // unplugged, Bluetooth device disconnected) instead of carrying
    // on out loud to whatever's left -- again, standard media-app
    // etiquette, and the specific thing ACTION_AUDIO_BECOMING_NOISY
    // exists for.
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                commandListener?.onPauseCommand()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ArkLogger.track(COMPONENT, "onCreate") {
            MediaNotificationFactory.ensureChannel(this)

            mediaSession = MediaSessionCompat(this, "ArkMediaSession").apply {
                setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        commandListener?.onPlayCommand()
                    }

                    override fun onPause() {
                        commandListener?.onPauseCommand()
                    }

                    override fun onStop() {
                        // No real native "stop" for a page video beyond
                        // pausing it -- there's nothing to release/tear
                        // down the way a local media player would.
                        commandListener?.onPauseCommand()
                    }

                    override fun onSeekTo(pos: Long) {
                        commandListener?.onSeekToCommand(pos)
                    }

                    override fun onFastForward() {
                        commandListener?.onFastForwardCommand()
                    }

                    override fun onRewind() {
                        commandListener?.onRewindCommand()
                    }
                })
                setPlaybackState(idlePlaybackState())
                isActive = true
            }

            registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        ArkLogger.track(COMPONENT, "onStartCommand") {
            // Whenever this service is (re)started -- MainActivity's own
            // startForegroundService() call the first time real playback
            // begins, or the system relaunching it to deliver a tapped
            // notification action/media-button PendingIntent -- Android
            // requires startForeground() to be called shortly after
            // onStartCommand() returns. Post a notification immediately;
            // updatePlaybackState()/updateMetadata() replace it with
            // fresher content moments later once MainActivity/JS report
            // the actual state.
            startForegroundWithNotification()
            intent?.let { MediaButtonReceiver.handleIntent(mediaSession, it) }
            START_NOT_STICKY
        }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        ArkLogger.track(COMPONENT, "onDestroy") {
            try {
                unregisterReceiver(becomingNoisyReceiver)
            } catch (t: Throwable) {
                // Not fatal -- can legitimately already be unregistered
                // if onDestroy somehow runs twice -- but worth a
                // warning if it happens for any other reason.
                ArkLogger.w(COMPONENT, "unregisterReceiver failed", t)
            }
            mediaSession.isActive = false
            mediaSession.release()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }

    fun setCommandListener(listener: CommandListener?) {
        commandListener = listener
    }

    /**
     * Mirrors the page's own `<video>` play/pause/position into the
     * session -- called from MainActivity whenever MEDIA_SESSION_JS
     * reports a play/pause/seeked/timeupdate event, so this reflects
     * what's *actually* happening on the page, not just the last
     * command a native control sent it.
     *
     * IMPORTANT: MEDIA_SESSION_JS reports playback state on every
     * throttled `timeupdate` tick (roughly once a second) for as long
     * as the video keeps playing, not just once when it actually
     * starts -- so this runs with `playing == true` continuously
     * during normal playback, not just on the false->true edge. That's
     * fine for the session/notification update below, which is
     * idempotent either way. It used to also matter for a native
     * requestAudioFocus() call gated on that edge -- removed per
     * docs/bugs-caught/BUG-0004-audio-focus-ping-pong.md, since holding
     * a native AudioFocusRequest for audio Chromium's own WebView media
     * stack already owns is what caused that bug, not how often this
     * method fired.
     */
    fun updatePlaybackState(playing: Boolean, positionMs: Long, playbackSpeed: Float) {
        ArkLogger.track(COMPONENT, "updatePlaybackState(playing=$playing)") {
            isPlaying = playing
            val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_STOP
            val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            mediaSession.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(state, positionMs, if (playbackSpeed > 0f) playbackSpeed else 1f)
                    .build()
            )
            if (playing) {
                startForegroundWithNotification()
            } else {
                updateNotification()
                // Demotes out of the foreground state but leaves the
                // notification up (now showing a "paused" transport
                // control) so the user can dismiss it manually, matching
                // how music apps behave once actually paused -- and lets
                // Android reclaim the process more readily than it would
                // while a foreground service is still active.
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            }
        }
    }

    /**
     * Updates title/duration/artwork -- called from MainActivity once
     * MEDIA_SESSION_JS reports the page's title (and MainActivity has
     * finished decoding `artwork`, if any og:image URL was found; see
     * MediaSessionCoordinator.loadArtwork()).
     */
    fun updateMetadata(title: String?, durationMs: Long, artwork: Bitmap?) {
        ArkLogger.track(COMPONENT, "updateMetadata") {
            val builder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: getString(R.string.app_name))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, SpaConfig.displayName)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
            if (artwork != null) {
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
            }
            mediaSession.setMetadata(builder.build())
            updateNotification()
        }
    }

    private fun idlePlaybackState(): PlaybackStateCompat = PlaybackStateCompat.Builder()
        .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PLAY_PAUSE)
        .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1f)
        .build()

    private fun updateNotification() {
        try {
            if (!::mediaSession.isInitialized) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                // No permission to actually show it -- the session itself
                // (and therefore lock-screen/Bluetooth/wired transport
                // control) still works without a visible notification.
                return
            }
            NotificationManagerCompat.from(this)
                .notify(MediaNotificationFactory.NOTIFICATION_ID, MediaNotificationFactory.build(this, mediaSession, isPlaying))
        } catch (t: Throwable) {
            ArkLogger.e(COMPONENT, "updateNotification failed", t)
        }
    }

    private fun startForegroundWithNotification() {
        try {
            val notification = MediaNotificationFactory.build(this, mediaSession, isPlaying)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(MediaNotificationFactory.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(MediaNotificationFactory.NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // A failed startForeground() here is serious -- Android
            // requires it soon after onStartCommand() -- so this is
            // an error, not a warning.
            ArkLogger.e(COMPONENT, "startForegroundWithNotification failed", t)
        }
    }

    private companion object {
        const val COMPONENT = "MediaPlaybackService"
    }
}
