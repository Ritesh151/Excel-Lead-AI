package com.optimatrix.gsmcall.audio

import android.content.Context
import androidx.media.MediaMetadataCompat
import androidx.media.session.MediaSessionCompat
import androidx.media.session.PlaybackStateCompat
import com.optimatrix.gsmcall.utils.LogStore

class MediaSessionController(context: Context) {
    private val mediaSession = MediaSessionCompat(context, "GsmCallSession").apply {
        setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY)
                .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                .build()
        )
        isActive = true
    }

    fun setMetadata(title: String) {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .build()
        )
        LogStore.log("MediaSession", "Metadata updated: $title")
    }

    fun deactivate() {
        mediaSession.isActive = false
        mediaSession.release()
        LogStore.log("MediaSession", "Media session deactivated")
    }
}
