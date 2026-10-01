package com.arjun.gander

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.ui.PlayerNotificationManager

/**
 * Play and pause on the lock screen, for a track that plays on with the screen off. Issue #38.
 *
 * Android draws a lock screen's controls from two things. A media session says what is playing
 * and takes the taps, from Android 13 on, and it is what a headset's button reaches on every
 * version. A media notification is what puts the controls on the lock screen at all, and before
 * Android 13 its buttons are the controls. Neither needs a permission: Android 13 asks before an
 * app may post a notification, but exempts one tied to a media session, and this is only ever that.
 *
 * The notification is up only while the track plays out of sight, which for Gander means with the
 * screen off, since leaving pauses it. With the viewer in front its own transport is the control,
 * and a second set in the shade would only repeat it. The session stays live while the viewer is
 * in front too, so a headset's button pauses this track rather than starting another app's.
 *
 * Android's own session rather than Media3's session library, which would keep itself in step
 * with the player but is a library Gander does not ship, for one file's play, pause and position.
 * The notification is Media3's own, from the player UI library Gander already ships.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class LockScreenControls(
    private val context: Context,
    private val player: Player,
    private val title: String,
) : Player.Listener {

    /** What the lock screen's buttons and a headset's do, from Android 13 on. */
    @androidx.annotation.VisibleForTesting
    internal val callback = object : MediaSession.Callback() {
        override fun onPlay() {
            Util.handlePlayButtonAction(player)
        }

        override fun onPause() {
            Util.handlePauseButtonAction(player)
        }

        override fun onSeekTo(pos: Long) {
            player.seekTo(pos)
        }
    }

    /** None while the viewer is out of sight with the track paused: see [leftPaused]. */
    @androidx.annotation.VisibleForTesting
    internal var session: MediaSession? = null
        private set

    private val notification = PlayerNotificationManager.Builder(
        context, NOTIFICATION_ID, CHANNEL_ID,
        object : PlayerNotificationManager.MediaDescriptionAdapter {
            override fun getCurrentContentTitle(player: Player): CharSequence = title

            // The viewer is what the screen shows once it is unlocked, so there is nowhere else to go
            override fun createCurrentContentIntent(player: Player): PendingIntent? = null

            override fun getCurrentContentText(player: Player): CharSequence? = null

            override fun getCurrentLargeIcon(
                player: Player,
                callback: PlayerNotificationManager.BitmapCallback
            ): Bitmap? = art
        }
    )
        .setChannelNameResourceId(R.string.audio_controls_channel)
        .setSmallIconResourceId(R.drawable.ic_notification)
        .build()
        .apply {
            // One file, so there is nothing to be previous or next to, as in the viewer
            setUsePreviousAction(false)
            setUseNextAction(false)
            setUseRewindAction(false)
            setUseFastForwardAction(false)
            // A file's name is no more private than the cover beside it, and the controls are
            // the reason this is up at all
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }

    /** The cover, once the viewer has read it from the file's tags. */
    var art: Bitmap? = null
        set(value) {
            field = value
            describe()
            notification.invalidate()
        }

    /** The length last put in the session, so the description is sent again only when it changes. */
    private var described = C.TIME_UNSET

    init {
        player.addListener(this)
        open()
    }

    private fun open() {
        val opened = MediaSession(context, SESSION_TAG).apply {
            setCallback(callback)
            isActive = true
        }
        session = opened
        notification.setMediaSessionToken(opened.sessionToken)
        describe()
        report()
    }

    /** The viewer is in front, with its own transport. */
    fun inFront() {
        notification.setPlayer(null)
        if (session == null) open()
    }

    /** The screen went off with the track playing, and it plays on. */
    fun playingOn() {
        notification.setPlayer(player)
    }

    /**
     * The track was paused as the viewer went out of sight, and the session goes. Android hands a
     * headset's button to whichever app played last, with its session active or not, so a session
     * kept would let the button start this track again behind whatever is in front.
     */
    fun leftPaused() {
        notification.setPlayer(null)
        session?.release()
        session = null
    }

    fun release() {
        leftPaused()
        player.removeListener(this)
    }

    override fun onEvents(player: Player, events: Player.Events) {
        if (player.duration != described) describe()
        report()
    }

    /** What is playing: the file's name, its length once known, and its cover if it has one. */
    private fun describe() {
        val session = session ?: return
        described = player.duration
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .apply {
                    if (described != C.TIME_UNSET) putLong(MediaMetadata.METADATA_KEY_DURATION, described)
                    art?.let { putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
                }
                .build()
        )
    }

    /**
     * Whether it is playing, and where. The lock screen moves its own position bar on from this at
     * the speed it is given, so a playing track is reported again only when something changes.
     */
    private fun report() {
        val session = session ?: return
        val state = when {
            player.isPlaying -> PlaybackState.STATE_PLAYING
            // Held up by a call is paused, as far as anyone looking is concerned
            player.playbackState == Player.STATE_BUFFERING && player.playWhenReady &&
                player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE ->
                PlaybackState.STATE_BUFFERING
            player.playbackState == Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO
                )
                .setState(
                    state, player.currentPosition,
                    if (state == PlaybackState.STATE_PLAYING) player.playbackParameters.speed else 0f
                )
                .build()
        )
    }

    private companion object {
        const val SESSION_TAG = "Gander"
        const val CHANNEL_ID = "audio_controls"

        /** Gander posts no other notification. */
        const val NOTIFICATION_ID = 1
    }
}
