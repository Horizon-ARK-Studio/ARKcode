package com.horizonarkstudio.arkware.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.horizonarkstudio.arkware.MainActivity
import com.horizonarkstudio.arkware.R
import com.horizonarkstudio.arkware.config.SpaConfig
import com.horizonarkstudio.arkware.logging.ArkLogger

/**
 * GoF Factory: builds the MediaStyle playback notification (and its
 * channel) for [com.horizonarkstudio.arkware.MediaPlaybackService]. Pulled out of
 * the Service itself so the Service's own job stays limited to owning
 * the MediaSessionCompat/audio-focus lifecycle -- "how the transport
 * notification looks" and "how the session behaves" are two separate
 * reasons to change (see docs/Foundational/CODE-STYLE.md Section 1),
 * even though they're closely related.
 */
object MediaNotificationFactory {

    private const val COMPONENT = "MediaNotificationFactory"
    const val NOTIFICATION_CHANNEL_ID = "arkware_playback"
    const val NOTIFICATION_ID = 1001

    /**
     * Creates the playback-controls notification channel. Safe to
     * call unconditionally/repeatedly -- channel creation is
     * idempotent.
     */
    fun ensureChannel(context: Context) = ArkLogger.track(COMPONENT, "ensureChannel") {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@track
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID, "Playback controls", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Playback controls for the video currently open in ${SpaConfig.displayName}"
            setShowBadge(false)
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    fun build(context: Context, mediaSession: MediaSessionCompat, isPlaying: Boolean): android.app.Notification =
        ArkLogger.track(COMPONENT, "build") {
            val contentIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val playPauseAction = if (isPlaying) {
                NotificationCompat.Action(
                    android.R.drawable.ic_media_pause, "Pause",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_PAUSE)
                )
            } else {
                NotificationCompat.Action(
                    android.R.drawable.ic_media_play, "Play",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_PLAY)
                )
            }
            val rewindAction = NotificationCompat.Action(
                android.R.drawable.ic_media_rew, "Rewind 10s",
                MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_REWIND)
            )
            val forwardAction = NotificationCompat.Action(
                android.R.drawable.ic_media_ff, "Forward 10s",
                MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_FAST_FORWARD)
            )

            val metadata = mediaSession.controller?.metadata
            val title = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: context.getString(R.string.app_name)
            val artwork = metadata?.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)

            NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(SpaConfig.displayName)
                .setLargeIcon(artwork)
                .setContentIntent(contentIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(isPlaying)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(rewindAction)
                .addAction(playPauseAction)
                .addAction(forwardAction)
                .setStyle(
                    MediaStyle()
                        .setMediaSession(mediaSession.sessionToken)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                .setDeleteIntent(
                    MediaButtonReceiver.buildMediaButtonPendingIntent(context, PlaybackStateCompat.ACTION_STOP)
                )
                .build()
        }
}
