package com.audiobookshelf.app.wearable

import android.content.Intent
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.nio.charset.StandardCharsets

private const val TAG = "PhoneWearListener"
import com.google.android.gms.wearable.DataMap

private const val TAG = "PhoneWearListener"
const val PATH_DOWNLOAD_REQUEST = "/download/request"
const val PATH_PLAYBACK_PROGRESS_SYNC = "/playback/progress/sync" // Matches Wear OS worker

// DataMap keys for progress sync (must match keys from Wear OS worker)
const val KEY_SYNC_MEDIA_ID = "mediaItemId"
const val KEY_SYNC_POSITION = "position"
const val KEY_SYNC_IS_FULLY_PLAYED = "isFullyPlayed"
const val KEY_SYNC_TIMESTAMP = "timestamp"


class PhoneWearableListenerService : WearableListenerService() {

    private var watchDownloadHandler: WatchDownloadHandler? = null
    private var watchProgressHandler: WatchProgressHandler? = null // Added

    override fun onCreate() {
        super.onCreate()
        watchDownloadHandler = WatchDownloadHandler(applicationContext)
        watchProgressHandler = WatchProgressHandler(applicationContext) // Added
        Log.d(TAG, "PhoneWearableListenerService created")
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "onMessageReceived: ${messageEvent.path} from ${messageEvent.sourceNodeId}")

        when (messageEvent.path) {
            PATH_DOWNLOAD_REQUEST -> {
                val mediaItemId = String(messageEvent.data, StandardCharsets.UTF_8)
                Log.i(TAG, "Received download request for mediaItemId: $mediaItemId")
                watchDownloadHandler?.handleDownloadRequestFromUI(mediaItemId) // Updated to use the UI entry point
            }
            PATH_PLAYBACK_PROGRESS_SYNC -> {
                val dataMap = DataMap.fromByteArray(messageEvent.data)
                val mediaItemId = dataMap.getString(KEY_SYNC_MEDIA_ID)
                val positionMs = dataMap.getLong(KEY_SYNC_POSITION)
                val isFullyPlayed = dataMap.getBoolean(KEY_SYNC_IS_FULLY_PLAYED)
                val watchTimestamp = dataMap.getLong(KEY_SYNC_TIMESTAMP) // Timestamp from watch

                if (mediaItemId != null) {
                    Log.i(TAG, "Received progress sync for mediaItemId: $mediaItemId, position: $positionMs ms, isFullyPlayed: $isFullyPlayed, watchTimestamp: $watchTimestamp")
                    watchProgressHandler?.handleWatchProgressUpdate(mediaItemId, positionMs, isFullyPlayed, watchTimestamp)
                } else {
                    Log.e(TAG, "Received progress sync with null mediaItemId.")
                }
            }
            // Add other message paths if needed (e.g., ACK for download completion from watch)
            else -> {
                Log.w(TAG, "Received unknown message path: ${messageEvent.path}")
                super.onMessageReceived(messageEvent)
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "PhoneWearableListenerService destroyed")
        super.onDestroy()
    }
}
