package com.audiobookshelf.app.wear

import android.content.ComponentName
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.ImageButton
import android.widget.TextView
import androidx.wear.activity.WearableActivity
import com.audiobookshelf.app.wear.R // Ensure R is imported

private const val TAG = "WearPlayerActivity"

// ComponentName for the phone app's PlayerNotificationService
private val PLAYER_SERVICE_COMPONENT_NAME =
    ComponentName("com.audiobookshelf.app", "com.audiobookshelf.app.player.PlayerNotificationService")

class PlayerActivity : WearableActivity() {

    companion object {
        const val EXTRA_MEDIA_ID = "media_id"
        const val EXTRA_MEDIA_TITLE = "media_title"
    }

    private lateinit var mediaTitleTextView: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var skipPreviousButton: ImageButton
    private lateinit var skipNextButton: ImageButton

    private lateinit var mediaBrowser: MediaBrowserCompat
    private var mediaController: MediaControllerCompat? = null

    private var currentMediaId: String? = null
    private var currentMediaTitle: String? = null

    private val connectionCallbacks = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            Log.d(TAG, "MediaBrowser connected")
            if (mediaBrowser.isConnected) {
                val token = mediaBrowser.sessionToken
                mediaController = MediaControllerCompat(this@PlayerActivity, token)
                MediaControllerCompat.setMediaController(this@PlayerActivity, mediaController)
                mediaController?.registerCallback(controllerCallback)

                // Initial UI update
                updateMetadata(mediaController?.metadata)
                updatePlaybackState(mediaController?.playbackState)

                // If a specific media item was requested to play
                currentMediaId?.let { mediaId ->
                    val currentPlayingId = mediaController?.metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
                    if (mediaId != currentPlayingId) {
                        mediaController?.transportControls?.playFromMediaId(mediaId, null)
                        Log.d(TAG, "Requested playFromMediaId: $mediaId")
                    } else {
                        Log.d(TAG, "Already playing requested mediaId: $mediaId or controller will handle it.")
                    }
                }

            } else {
                Log.w(TAG, "onConnected: MediaBrowser is not connected")
            }
        }

        override fun onConnectionSuspended() {
            Log.d(TAG, "MediaBrowser connection suspended")
            mediaController?.unregisterCallback(controllerCallback)
            mediaController = null
        }

        override fun onConnectionFailed() {
            Log.e(TAG, "MediaBrowser connection failed")
        }
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            Log.d(TAG, "Playback state changed: $state")
            updatePlaybackState(state)
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            Log.d(TAG, "Metadata changed: ${metadata?.description?.title}")
            updateMetadata(metadata)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        setAmbientEnabled() // Enable ambient mode

        mediaTitleTextView = findViewById(R.id.media_title_text_view)
        playPauseButton = findViewById(R.id.play_pause_button)
        skipPreviousButton = findViewById(R.id.skip_previous_button)
        skipNextButton = findViewById(R.id.skip_next_button)

        currentMediaId = intent.getStringExtra(EXTRA_MEDIA_ID)
        currentMediaTitle = intent.getStringExtra(EXTRA_MEDIA_TITLE)

        mediaTitleTextView.text = currentMediaTitle ?: "Loading..."

        mediaBrowser = MediaBrowserCompat(
            this,
            PLAYER_SERVICE_COMPONENT_NAME,
            connectionCallbacks,
            null // Optional root hints Bundle
        )

        playPauseButton.setOnClickListener {
            val pbState = mediaController?.playbackState?.state
            if (pbState == PlaybackStateCompat.STATE_PLAYING) {
                mediaController?.transportControls?.pause()
            } else {
                mediaController?.transportControls?.play()
            }
        }

        skipPreviousButton.setOnClickListener {
            mediaController?.transportControls?.skipToPrevious()
        }

        skipNextButton.setOnClickListener {
            mediaController?.transportControls?.skipToNext()
        }
        Log.d(TAG, "PlayerActivity onCreate. Media ID: $currentMediaId, Title: $currentMediaTitle")
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "PlayerActivity onStart")
        if (!mediaBrowser.isConnected) {
            Log.d(TAG, "Connecting to MediaBrowser")
            mediaBrowser.connect()
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "PlayerActivity onStop")
        mediaController?.unregisterCallback(controllerCallback)
        if (mediaBrowser.isConnected) {
            Log.d(TAG, "Disconnecting from MediaBrowser")
            mediaBrowser.disconnect()
        }
    }

    private fun updatePlaybackState(state: PlaybackStateCompat?) {
        when (state?.state) {
            PlaybackStateCompat.STATE_PLAYING -> {
                playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
            }
            PlaybackStateCompat.STATE_PAUSED,
            PlaybackStateCompat.STATE_STOPPED,
            PlaybackStateCompat.STATE_NONE -> {
                playPauseButton.setImageResource(android.R.drawable.ic_media_play)
            }
            else -> {
                playPauseButton.setImageResource(android.R.drawable.ic_media_play) // Default
            }
        }
    }

    private fun updateMetadata(metadata: MediaMetadataCompat?) {
        val title = metadata?.description?.title ?: currentMediaTitle ?: "Unknown Title"
        mediaTitleTextView.text = title
        // You could also update an ImageView with metadata?.description?.iconBitmap or iconUri
    }
}
