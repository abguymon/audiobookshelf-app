package com.audiobookshelf.app.wear.playback

import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaSessionCompat
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import com.audiobookshelf.app.wear.PlayerActivity // Assuming PlayerActivity can show playback UI
import com.audiobookshelf.app.wear.R
import com.audiobookshelf.app.wear.WearApplication
import com.audiobookshelf.app.wear.db.AppDatabase
import com.audiobookshelf.app.wear.db.DownloadedItem
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "LocalPlaybackService"
private const val LOCAL_MEDIA_ROOT = "__LOCAL_ROOT__"
private const val NOTIFICATION_ID = 123 // Arbitrary notification ID
private const val CHANNEL_ID = "local_playback_channel"
private const val CHANNEL_NAME = "Local Playback"

class LocalPlaybackService : MediaBrowserServiceCompat() {

    private lateinit var localPlayer: ExoPlayer
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: PlayerNotificationManager
    private var currentPlayingItem: DownloadedItem? = null

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val db: AppDatabase by lazy { (application as WearApplication).database }


    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: LocalPlaybackService creating.")

        localPlayer = SimpleExoPlayer.Builder(applicationContext).build().apply {
            addListener(playerListener)
            // TODO: Set audio attributes if needed (e.g., for speech)
        }

        mediaSession = MediaSessionCompat(applicationContext, TAG).apply {
            setCallback(mediaSessionCallback)
            isActive = true
        }
        sessionToken = mediaSession.sessionToken // This is crucial

        setupNotificationManager()
        Log.d(TAG, "onCreate: MediaSession and NotificationManager initialized. Token: ${mediaSession.sessionToken}")
    }

    private fun setupNotificationManager() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        notificationManager = PlayerNotificationManager.Builder(
            this,
            NOTIFICATION_ID,
            CHANNEL_ID
        )
            .setMediaDescriptionAdapter(DescriptionAdapter())
            .setNotificationListener(object : PlayerNotificationManager.NotificationListener {
                override fun onNotificationPosted(notificationId: Int, notification: Notification, ongoing: Boolean) {
                    if (ongoing) {
                        ContextCompat.startForegroundService(applicationContext, Intent(applicationContext, this@LocalPlaybackService.javaClass))
                        startForeground(notificationId, notification)
                        Log.d(TAG, "Notification posted, service started in foreground.")
                    } else {
                        stopForeground(false) // Keep notification if playback paused, remove if stopped
                        Log.d(TAG, "Notification dismissed, service stopped foreground (allow dismiss: false).")
                    }
                }

                override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
                    Log.d(TAG, "Notification cancelled by user: $dismissedByUser")
                    stopSelf() // Stop service if notification is cancelled
                }
            })
            .build().apply {
                setPlayer(localPlayer)
                setMediaSessionToken(mediaSession.sessionToken)
                setUseNextAction(false) // Customize as needed
                setUsePreviousAction(false)
                // Add other actions as needed
            }
    }


    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        Log.d(TAG, "onGetRoot: clientPackageName=$clientPackageName, clientUid=$clientUid")
        return BrowserRoot(LOCAL_MEDIA_ROOT, null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        Log.d(TAG, "onLoadChildren: parentId=$parentId")
        if (parentId != LOCAL_MEDIA_ROOT) {
            result.sendResult(null)
            return
        }

        serviceScope.launch {
            val items = db.downloadedItemDao().getAllBlocking() // Assuming this is a suspend fun or use LiveData
            val mediaItems = items.mapNotNull { item ->
                if (item.isDownloadComplete() && item.localAudioPath != null) {
                    val description = MediaDescriptionCompat.Builder()
                        .setMediaId(item.id)
                        .setTitle(item.title ?: "Unknown Title")
                        .setSubtitle(item.author ?: "Unknown Author")
                        // .setIconUri(item.localCoverPath?.let { Uri.fromFile(File(it)) }) // If you have cover art
                        .build()
                    MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
                } else null
            }.toMutableList()
            result.sendResult(mediaItems)
            Log.d(TAG, "onLoadChildren: Sent ${mediaItems.size} items.")
        }
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            mediaId ?: return
            Log.d(TAG, "onPlayFromMediaId: mediaId=$mediaId")
            serviceScope.launch {
                currentPlayingItem = db.downloadedItemDao().getById(mediaId)
                currentPlayingItem?.let { item ->
                    if (item.localAudioPath != null) {
                        val mediaItem = MediaItem.fromUri(Uri.fromFile(File(item.localAudioPath)))
                        localPlayer.setMediaItem(mediaItem)
                        localPlayer.prepare()
                        localPlayer.seekTo(item.lastPlayedPosition) // Restore position
                        localPlayer.play() // This will trigger onIsPlayingChanged -> notification update

                        updateMediaSessionMetadata(item)
                        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING, item.lastPlayedPosition)
                        Log.i(TAG, "Playing item: ${item.title}")
                    } else {
                        Log.e(TAG, "Local audio path is null for mediaId: $mediaId")
                        updatePlaybackState(PlaybackStateCompat.STATE_ERROR, 0)
                    }
                } ?: run {
                    Log.e(TAG, "Could not find item with mediaId: $mediaId in DB")
                    updatePlaybackState(PlaybackStateCompat.STATE_ERROR, 0)
                }
            }
        }

        override fun onPlay() {
            Log.d(TAG, "onPlay")
            if (localPlayer.playbackState == Player.STATE_ENDED) {
                // Re-prepare if ended, or play from mediaId again if item known
                currentPlayingItem?.let { onPlayFromMediaId(it.id, null) }
            } else {
                localPlayer.play()
            }
        }

        override fun onPause() {
            Log.d(TAG, "onPause")
            localPlayer.pause()
            saveCurrentPlaybackPosition()
        }

        override fun onStop() {
            Log.d(TAG, "onStop")
            localPlayer.stop()
            saveCurrentPlaybackPosition()
            currentPlayingItem = null
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED, 0)
            stopForeground(true) // True to remove notification
            stopSelf()
        }

        override fun onSeekTo(pos: Long) {
            Log.d(TAG, "onSeekTo: position=$pos")
            localPlayer.seekTo(pos)
            updatePlaybackState(localPlayer.playbackState, pos) // Update with current player state
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            updatePlaybackState(playbackState, localPlayer.currentPosition)
            if (playbackState == Player.STATE_ENDED) {
                currentPlayingItem?.let { item ->
                    item.isFullyPlayed = true
                    item.lastPlayedPosition = 0 // Reset position
                    item.needsSync = true // Mark for sync
                    serviceScope.launch { db.downloadedItemDao().update(item) }
                }
                Log.d(TAG, "Playback ended for ${currentPlayingItem?.title}, marked for sync.")
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            updatePlaybackState(state, localPlayer.currentPosition)
            if (!isPlaying) {
                saveCurrentPlaybackPosition()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Player error: ${error.message}", error)
            updatePlaybackState(PlaybackStateCompat.STATE_ERROR, localPlayer.currentPosition)
        }
    }

    private fun updatePlaybackState(exoPlayerState: Int, positionMs: Long) {
        val sessionPlaybackState = when (exoPlayerState) {
            Player.STATE_IDLE -> PlaybackStateCompat.STATE_NONE
            Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            Player.STATE_READY -> if (localPlayer.playWhenReady) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED // Or PAUSED if you want to allow restart
            else -> PlaybackStateCompat.STATE_NONE
        }
        updatePlaybackState(sessionPlaybackState, positionMs)
    }
    
    private fun updatePlaybackState(state: Int, positionMs: Long) {
        val playbackSpeed = localPlayer.playbackParameters.speed
        val newState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(state, positionMs, playbackSpeed)
            .build()
        mediaSession.setPlaybackState(newState)

        // Update notification based on state
        if (state == PlaybackStateCompat.STATE_PLAYING) {
            // NotificationManager handles starting foreground via listener
        } else if (state == PlaybackStateCompat.STATE_PAUSED) {
            notificationManager.setPlayer(localPlayer) // Ensure notification updates
            stopForeground(false) // Keep notification, allow swipe dismiss
        } else if (state == PlaybackStateCompat.STATE_STOPPED || state == PlaybackStateCompat.STATE_NONE || state == PlaybackStateCompat.STATE_ERROR) {
            stopForeground(true) // Remove notification
        }
    }

    private fun updateMediaSessionMetadata(item: DownloadedItem) {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, item.id)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, item.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, item.author) // Or AlbumArtist
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, item.duration ?: 0L)

        item.localCoverPath?.let { coverPath ->
            try {
                val bitmap = BitmapFactory.decodeFile(coverPath)
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Error decoding cover for metadata: $coverPath", e)
            }
        }
        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun saveCurrentPlaybackPosition() {
        currentPlayingItem?.let { item ->
            val position = localPlayer.currentPosition
            // Only save and mark for sync if position is meaningful
            if (position > 0 && position < (item.duration ?: Long.MAX_VALUE)) {
                if (item.lastPlayedPosition != position) { // Only update if position actually changed
                    item.lastPlayedPosition = position
                    item.needsSync = true // Mark for sync
                    serviceScope.launch {
                        db.downloadedItemDao().update(item)
                        Log.d(TAG, "Saved playback position $position for ${item.title}, marked for sync.")
                    }
                }
            } else if (localPlayer.playbackState == Player.STATE_IDLE && item.lastPlayedPosition != 0L && !item.isFullyPlayed) {
                // If playback stopped abruptly or before actual end, ensure current position is saved
                // and needsSync is true, unless it's already 0 and not fully played (meaning it was reset).
                if (item.lastPlayedPosition != position) { // Check if it's different from already saved position
                    item.lastPlayedPosition = position // This might be 0 if stopped very early.
                    item.needsSync = true
                     serviceScope.launch {
                        db.downloadedItemDao().update(item)
                        Log.d(TAG, "Saved (potentially zero) playback position $position for ${item.title} due to stop, marked for sync.")
                    }
                }
            }
        }
    }

    private inner class DescriptionAdapter : PlayerNotificationManager.MediaDescriptionAdapter {
        override fun getCurrentContentTitle(player: Player): CharSequence {
            return currentPlayingItem?.title ?: "Unknown Title"
        }

        override fun createCurrentContentIntent(player: Player): PendingIntent? {
            // Intent to open PlayerActivity when notification is tapped
            val intent = Intent(applicationContext, PlayerActivity::class.java).apply {
                // Pass necessary extras for PlayerActivity to resume correctly
                putExtra(PlayerActivity.EXTRA_LOCAL_MEDIA_ID, currentPlayingItem?.id)
                putExtra(PlayerActivity.EXTRA_MEDIA_TITLE, currentPlayingItem?.title)
            }
            return PendingIntent.getActivity(
                applicationContext, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        override fun getCurrentContentText(player: Player): CharSequence? {
            return currentPlayingItem?.author ?: "Unknown Artist"
        }

        override fun getCurrentLargeIcon(player: Player, callback: PlayerNotificationManager.BitmapCallback): Bitmap? {
            currentPlayingItem?.localCoverPath?.let {
                val file = File(it)
                if (file.exists()) {
                    return BitmapFactory.decodeFile(it)
                }
            }
            // Return a default icon if no cover is available
            return BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved: Stopping service and playback.")
        localPlayer.stop() // Ensure player stops
        saveCurrentPlaybackPosition()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Releasing resources.")
        mediaSession.release()
        localPlayer.removeListener(playerListener)
        localPlayer.release()
        notificationManager.setPlayer(null) // Important to release notification manager
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent): android.os.IBinder? {
        // This is for clients binding to the service (e.g., for custom commands).
        // PlayerActivity will use MediaBrowserCompat to connect.
        Log.d(TAG, "onBind called with intent: $intent, action: ${intent.action}")
        if (SERVICE_INTERFACE == intent.action) {
            return super.onBind(intent)
        }
        // Return null if the intent is not for MediaBrowserService.
        // Or handle custom binding if you have other interfaces.
        return null
    }
}
