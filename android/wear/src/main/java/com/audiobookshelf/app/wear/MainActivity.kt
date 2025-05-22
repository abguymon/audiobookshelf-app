package com.audiobookshelf.app.wear

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.activity.WearableActivity
import androidx.wear.widget.WearableRecyclerView
import com.audiobookshelf.app.wear.R // Make sure R is imported if not automatically

private const val TAG = "WearMainActivity"

// ComponentName for the phone app's PlayerNotificationService
private val PLAYER_SERVICE_COMPONENT_NAME =
    ComponentName("com.audiobookshelf.app", "com.audiobookshelf.app.player.PlayerNotificationService")
private const val ROOT_MEDIA_ID = "/"

class MainActivity : WearableActivity() {

    private lateinit var mediaBrowser: MediaBrowserCompat
    private var mediaController: MediaControllerCompat? = null
    private lateinit var mediaListView: WearableRecyclerView
    private lateinit var mediaItemAdapter: MediaItemAdapter

    private val connectionCallbacks = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            Log.d(TAG, "MediaBrowser connected")
            if (mediaBrowser.isConnected) {
                val token = mediaBrowser.sessionToken
                mediaController = MediaControllerCompat(this@MainActivity, token)
                MediaControllerCompat.setMediaController(this@MainActivity, mediaController)
                mediaController?.registerCallback(controllerCallback)

                // Subscribe to the root to get top-level items
                mediaBrowser.subscribe(mediaBrowser.root, subscriptionCallback)
                Log.d(TAG, "Subscribed to root: ${mediaBrowser.root}")
            } else {
                Log.w(TAG, "onConnected: MediaBrowser is not connected")
            }
        }

        override fun onConnectionSuspended() {
            Log.d(TAG, "MediaBrowser connection suspended")
            mediaController?.unregisterCallback(controllerCallback)
            mediaController = null
            // TODO: Handle UI changes or show a message
        }

        override fun onConnectionFailed() {
            Log.e(TAG, "MediaBrowser connection failed")
            // TODO: Handle UI changes or show an error message
        }
    }

    private val subscriptionCallback = object : MediaBrowserCompat.SubscriptionCallback() {
        override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowserCompat.MediaItem>) {
            Log.d(TAG, "onChildrenLoaded for parentId: $parentId, children count: ${children.size}")
            if (children.isEmpty()) {
                Log.d(TAG, "No children found for $parentId")
                // TODO: Handle empty list, maybe show a message
                return
            }
            mediaItemAdapter.setMediaItems(children)
        }

        override fun onError(parentId: String) {
            Log.e(TAG, "Error loading children for $parentId")
            // TODO: Handle error, show a message
        }
    }

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            Log.d(TAG, "Playback state changed: $state")
            // TODO: Update UI based on playback state if needed in this activity
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            Log.d(TAG, "Metadata changed: ${metadata?.description?.title}")
            // TODO: Update UI based on metadata if needed in this activity
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setAmbientEnabled() // Enable ambient mode

        mediaListView = findViewById(R.id.media_list_view)
        mediaListView.isEdgeItemsCenteringEnabled = true // Recommended for Wear OS lists
        // mediaListView.layoutManager = WearableLinearLayoutManager(this) // Already set in XML for WearableRecyclerView

        mediaItemAdapter = MediaItemAdapter(emptyList()) { mediaItem ->
            onMediaItemSelected(mediaItem)
        }
        mediaListView.adapter = mediaItemAdapter

        mediaBrowser = MediaBrowserCompat(
            this,
            PLAYER_SERVICE_COMPONENT_NAME,
            connectionCallbacks,
            null // Optional root hints Bundle
        )
        Log.d(TAG, "MainActivity onCreate")
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "MainActivity onStart")
        if (!mediaBrowser.isConnected) {
            Log.d(TAG, "Connecting to MediaBrowser")
            mediaBrowser.connect()
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "MainActivity onStop")
        mediaController?.unregisterCallback(controllerCallback)
        if (mediaBrowser.isConnected) {
            Log.d(TAG, "Disconnecting from MediaBrowser")
            // Unsubscribe from all subscriptions before disconnecting
            // It's good practice, though MediaBrowserCompat might handle some of this.
            // For simplicity, we are unsubscribing from root here.
            // A more robust solution would track all active subscriptions.
            if (mediaBrowser.root.isNotEmpty()) {
                 mediaBrowser.unsubscribe(mediaBrowser.root)
            }
            mediaBrowser.disconnect()
        }
    }

    private fun onMediaItemSelected(mediaItem: MediaBrowserCompat.MediaItem) {
        Log.d(TAG, "Media item selected: ${mediaItem.description.title}, ID: ${mediaItem.mediaId}")
        if (mediaItem.isBrowsable) {
            Log.d(TAG, "Item is BROWSABLE, subscribing to ${mediaItem.mediaId}")
            mediaBrowser.subscribe(mediaItem.mediaId!!, subscriptionCallback)
        } else if (mediaItem.isPlayable) {
            Log.d(TAG, "Item is PLAYABLE, playing ${mediaItem.mediaId}")
            mediaController?.transportControls?.playFromMediaId(mediaItem.mediaId, null)
            // Start PlayerActivity
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_MEDIA_TITLE, mediaItem.description.title)
                putExtra(PlayerActivity.EXTRA_MEDIA_ID, mediaItem.mediaId)
                // Potentially pass more data like artist, album art URI etc.
            }
            startActivity(intent)
        } else {
            Log.w(TAG, "Media item is neither browsable nor playable: ${mediaItem.mediaId}")
        }
    }
}

class MediaItemAdapter(
    private var mediaItems: List<MediaBrowserCompat.MediaItem>,
    private val itemClickListener: (MediaBrowserCompat.MediaItem) -> Unit
) : RecyclerView.Adapter<MediaItemAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val titleTextView: TextView = view.findViewById(R.id.media_item_title)
        val iconImageView: ImageView = view.findViewById(R.id.media_item_icon) // Assuming you have an icon
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_media, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val mediaItem = mediaItems[position]
        holder.titleTextView.text = mediaItem.description.title ?: "Unknown Title"
        // TODO: Load icon using mediaItem.description.iconUri or a placeholder
        // For now, we can set a placeholder or leave it as is if the XML has a default src
        // holder.iconImageView.setImageURI(mediaItem.description.iconUri)
        holder.itemView.setOnClickListener {
            itemClickListener(mediaItem)
        }
    }

    override fun getItemCount() = mediaItems.size

    fun setMediaItems(newMediaItems: List<MediaBrowserCompat.MediaItem>) {
        mediaItems = newMediaItems
        notifyDataSetChanged() // Consider using DiffUtil for better performance
        Log.d("MediaItemAdapter", "Media items updated, count: ${mediaItems.size}")
    }
}
