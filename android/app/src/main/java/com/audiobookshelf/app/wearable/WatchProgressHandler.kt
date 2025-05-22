package com.audiobookshelf.app.wearable

import android.content.Context
import android.util.Log
import com.audiobookshelf.app.server.ApiHandler
import com.audiobookshelf.app.data.LibraryItem // Assuming this is the correct data class
import com.getcapacitor.JSObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "WatchProgressHandler"

class WatchProgressHandler(private val context: Context) {

    private val apiHandler by lazy { ApiHandler(context) }
    // MediaManager might be needed if complex logic is required to get item details,
    // but for now, ApiHandler.getLibraryItem might suffice for duration.
    // private val mediaManager by lazy { MediaManager(apiHandler, context) }


    fun handleWatchProgressUpdate(
        mediaItemId: String,
        positionMs: Long,
        isFullyPlayed: Boolean,
        watchTimestamp: Long // Received from watch, can be used for logging or advanced conflict resolution
    ) {
        Log.i(
            TAG,
            "Handling progress update for mediaItemId: $mediaItemId, position: $positionMs ms, isFullyPlayed: $isFullyPlayed, watchTimestamp: $watchTimestamp"
        )

        // Run network operations off the main thread
        CoroutineScope(Dispatchers.IO).launch {
            // Step 1: Fetch item details to get duration (and potentially compare timestamps later)
            apiHandler.getLibraryItem(mediaItemId) { libraryItem ->
                if (libraryItem == null) {
                    Log.e(TAG, "Failed to fetch library item details for $mediaItemId. Cannot sync progress.")
                    // Optionally, send a NACK to watch or retry later? For now, just log.
                    return@getLibraryItem
                }

                val durationSeconds = libraryItem.media.duration ?: 0.0
                val currentTimeSeconds = positionMs / 1000.0

                // Basic conflict avoidance: If server's progress is much newer than watch's, maybe don't update.
                // For now, we'll proceed with the update. More sophisticated logic would compare
                // libraryItem.mediaProgress?.lastUpdate with watchTimestamp.
                // The server itself should also have logic to prevent overwriting newer progress.

                Log.d(TAG, "Fetched item duration: $durationSeconds seconds for $mediaItemId")

                // Step 2: Prepare payload for server update
                val updatePayload = JSObject().apply {
                    put("currentTime", currentTimeSeconds)
                    put("duration", durationSeconds) // Server might need this to correctly calculate percentage
                    put("isFinished", isFullyPlayed)
                    put("lastUpdate", System.currentTimeMillis()) // Phone's timestamp for this update attempt
                    // The server should ideally use its own timestamp upon successful update.
                }

                Log.d(TAG, "Update payload for $mediaItemId: $updatePayload")

                // Step 3: Update server
                // Assuming mediaItemId from watch is the libraryItemId and episodeId is null for now.
                // If mediaItemId can be an episodeId, this needs adjustment.
                apiHandler.updateMediaProgress(mediaItemId, null, updatePayload) {
                    // The callback for updateMediaProgress in ApiHandler is empty,
                    // it just logs. We'll assume success if no exception, or rely on server logs.
                    // For more robust feedback, ApiHandler's patchRequest callback would need to provide success/failure.
                    Log.i(TAG, "Attempted to update media progress for $mediaItemId on server.")
                    // TODO: Optionally send ACK to watch if server update is confirmed successful.
                }
            }
        }
    }
}
