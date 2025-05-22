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

// ComponentName for the local playback service
private val LOCAL_PLAYBACK_SERVICE_COMPONENT_NAME by lazy {
    ComponentName("com.audiobookshelf.app.wear", "com.audiobookshelf.app.wear.playback.LocalPlaybackService")
}
class PlayerActivity : WearableActivity() {

    companion object {
        const val EXTRA_MEDIA_ID = "media_id" // Used for both remote and as reference for local
        const val EXTRA_MEDIA_TITLE = "media_title"
        const val EXTRA_LOCAL_AUDIO_URI = "local_audio_uri"
        const val EXTRA_LOCAL_MEDIA_ID = "local_media_id" // Alternative/preferred way to specify local item
    }

    private lateinit var mediaTitleTextView: TextView
    private lateinit var playPauseButton: ImageButton
    private lateinit var skipPreviousButton: ImageButton
    private lateinit var skipNextButton: ImageButton

    private lateinit var mediaBrowser: MediaBrowserCompat
    private var mediaController: MediaControllerCompat? = null
    // Removed localPlayer and db instance variables

    private var currentMediaId: String? = null
    private var currentMediaTitle: String? = null
    private var isLocalPlayback = false // Determines which service to connect to

    private val connectionCallbacks = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            Log.d(TAG, "MediaBrowser connected to: ${mediaBrowser.serviceComponent}")
            if (mediaBrowser.isConnected) {
                val token = mediaBrowser.sessionToken
                mediaController = MediaControllerCompat(this@PlayerActivity, token)
                MediaControllerCompat.setMediaController(this@PlayerActivity, mediaController)
                mediaController?.registerCallback(controllerCallback)

                // Initial UI update based on the new controller
                updateMetadata(mediaController?.metadata)
                updatePlaybackState(mediaController?.playbackState)

                // If a specific media item was requested to play
                currentMediaId?.let { mediaId ->
                    val currentPlayingId = mediaController?.metadata?.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
                    // Only play if it's not already the current item, or if playback is stopped/none
                    val currentPlaybackState = mediaController?.playbackState?.state
                    if (mediaId != currentPlayingId ||
                        currentPlaybackState == PlaybackStateCompat.STATE_NONE ||
                        currentPlaybackState == PlaybackStateCompat.STATE_STOPPED) {
                        mediaController?.transportControls?.playFromMediaId(mediaId, null)
                        Log.d(TAG, "Requested playFromMediaId: $mediaId to service: ${mediaBrowser.serviceComponent}")
                    } else {
                        Log.d(TAG, "Already playing or paused on mediaId: $mediaId, not re-requesting playFromMediaId.")
                    }
                }
            } else {
                Log.w(TAG, "onConnected: MediaBrowser is NOT connected despite callback.")
            }
        }

        override fun onConnectionSuspended() {
            Log.w(TAG, "MediaBrowser connection suspended to: ${mediaBrowser.serviceComponent}")
            mediaController?.unregisterCallback(controllerCallback)
            mediaController = null
            // TODO: Handle UI (e.g., show disconnected state)
        }

        override fun onConnectionFailed() {
            Log.e(TAG, "MediaBrowser connection failed to: ${mediaBrowser.serviceComponent}")
            mediaController = null
            // TODO: Handle UI (e.g., show error message)
        }
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            Log.d(TAG, "Playback state changed: $state for service: ${mediaBrowser.serviceComponent}")
            updatePlaybackState(state)
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            Log.d(TAG, "Metadata changed: ${metadata?.description?.title} for service: ${mediaBrowser.serviceComponent}")
            updateMetadata(metadata)
        }
    }

    // Removed localPlayerListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        setAmbientEnabled()

        mediaTitleTextView = findViewById(R.id.media_title_text_view)
        playPauseButton = findViewById(R.id.play_pause_button)
        skipPreviousButton = findViewById(R.id.skip_previous_button)
        skipNextButton = findViewById(R.id.skip_next_button)

        currentMediaId = intent.getStringExtra(EXTRA_MEDIA_ID) ?: intent.getStringExtra(EXTRA_LOCAL_MEDIA_ID)
        currentMediaTitle = intent.getStringExtra(EXTRA_MEDIA_TITLE)
        val localAudioUriString = intent.getStringExtra(EXTRA_LOCAL_AUDIO_URI) // Still useful to confirm it's a local request intent

        mediaTitleTextView.text = currentMediaTitle ?: "Loading..."

        // Determine if this is for local playback
        // EXTRA_LOCAL_MEDIA_ID is now the primary indicator for local playback intent.
        // EXTRA_LOCAL_AUDIO_URI was also used, ensure consistency or pick one.
        // Let's prioritize EXTRA_LOCAL_MEDIA_ID if provided by DownloadsActivity now.
        isLocalPlayback = intent.hasExtra(EXTRA_LOCAL_MEDIA_ID) || localAudioUriString != null

        val serviceComponent = if (isLocalPlayback) {
            Log.i(TAG, "Configuring for local playback. Media ID: $currentMediaId")
            LOCAL_PLAYBACK_SERVICE_COMPONENT_NAME
        } else {
            Log.i(TAG, "Configuring for remote playback. Media ID: $currentMediaId")
            PLAYER_SERVICE_COMPONENT_NAME
        }

        mediaBrowser = MediaBrowserCompat(
            this,
            serviceComponent,
            connectionCallbacks,
            null // Optional root hints Bundle
        )

        setupPlaybackControls()
        Log.d(TAG, "PlayerActivity onCreate. Media ID: $currentMediaId, Title: $currentMediaTitle, IsLocal: $isLocalPlayback. Connecting to: $serviceComponent")
    }

    // Removed initializeLocalPlayer()

    private fun setupPlaybackControls() {
        playPauseButton.setOnClickListener {
            mediaController?.let { controller ->
                val pbState = controller.playbackState?.state
                if (pbState == PlaybackStateCompat.STATE_PLAYING) {
                    controller.transportControls.pause()
                } else {
                    controller.transportControls.play()
                }
            }
        }

        skipPreviousButton.setOnClickListener {
            mediaController?.transportControls?.skipToPrevious() // Or seekBackward for local
        }

        skipNextButton.setOnClickListener {
            mediaController?.transportControls?.skipToNext() // Or seekForward for local
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "PlayerActivity onStart. IsLocal: $isLocalPlayback. Connecting MediaBrowser.")
        if (!mediaBrowser.isConnected) {
            mediaBrowser.connect()
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "PlayerActivity onStop. IsLocal: $isLocalPlayback.")
        mediaController?.unregisterCallback(controllerCallback)
        if (mediaBrowser.isConnected) {
            mediaBrowser.disconnect()
        }
        // Saving playback position for local files is now handled by LocalPlaybackService
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "PlayerActivity onDestroy.")
        // MediaBrowser is disconnected in onStop, local player is managed by service
    }

    // Removed updateLocalPlaybackState

    private fun updatePlaybackState(state: PlaybackStateCompat?) {
        when (state?.state) {
            PlaybackStateCompat.STATE_PLAYING -> playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
            PlaybackStateCompat.STATE_PAUSED,
            PlaybackStateCompat.STATE_STOPPED,
            PlaybackStateCompat.STATE_NONE -> playPauseButton.setImageResource(android.R.drawable.ic_media_play)
            else -> playPauseButton.setImageResource(android.R.drawable.ic_media_play) // Default
        }
    }

    private fun updateMetadata(metadata: MediaMetadataCompat?) {
        val title = metadata?.description?.title ?: currentMediaTitle ?: "Unknown Title"
        mediaTitleTextView.text = title
        // TODO: Update cover art if available from metadata (e.g., metadata.description.iconUri or iconBitmap)
    }
}
