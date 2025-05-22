package com.audiobookshelf.app.wear.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.audiobookshelf.app.wear.WearApplication
import com.audiobookshelf.app.wear.db.AppDatabase
import com.audiobookshelf.app.wear.db.DownloadedItem
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private const val TAG = "ProgressSyncWorker"
const val PATH_PLAYBACK_PROGRESS_SYNC = "/playback/progress/sync"

// DataMap keys for progress sync
const val KEY_SYNC_MEDIA_ID = "mediaItemId"
const val KEY_SYNC_POSITION = "position" // in milliseconds
const val KEY_SYNC_IS_FULLY_PLAYED = "isFullyPlayed"
const val KEY_SYNC_TIMESTAMP = "timestamp" // When this progress was recorded on watch

class ProgressSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val db: AppDatabase by lazy { (applicationContext as WearApplication).database }
    private val messageClient by lazy { Wearable.getMessageClient(applicationContext) }
    private val nodeClient by lazy { Wearable.getNodeClient(applicationContext) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(applicationContext) }

    companion object {
        const val WORKER_NAME = "ProgressSyncWorker"
        // Define a capability string that the phone app will advertise.
        private const val PHONE_APP_CAPABILITY_NAME = "audiobookshelf_phone_app"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork: Starting progress sync worker.")

        return withContext(Dispatchers.IO) {
            try {
                val itemsToSync = db.downloadedItemDao().getItemsNeedingSync() // New DAO method needed
                if (itemsToSync.isEmpty()) {
                    Log.i(TAG, "No items need syncing.")
                    return@withContext Result.success()
                }

                Log.i(TAG, "Found ${itemsToSync.size} items to sync.")

                // Find a connected phone node that has the app.
                val phoneNodeId = getCapablePhoneNodeId()
                if (phoneNodeId == null) {
                    Log.w(TAG, "No capable phone node found. Retrying later.")
                    return@withContext Result.retry() // Retry if no node found
                }
                Log.i(TAG, "Found capable phone node: $phoneNodeId")


                var allItemsSuccessfullySent = true
                for (item in itemsToSync) {
                    val dataMap = DataMap().apply {
                        putString(KEY_SYNC_MEDIA_ID, item.id)
                        putLong(KEY_SYNC_POSITION, item.lastPlayedPosition)
                        putBoolean(KEY_SYNC_IS_FULLY_PLAYED, item.isFullyPlayed)
                        putLong(KEY_SYNC_TIMESTAMP, System.currentTimeMillis()) // Current time as sync attempt time
                    }

                    try {
                        messageClient.sendMessage(
                            phoneNodeId,
                            PATH_PLAYBACK_PROGRESS_SYNC,
                            dataMap.toByteArray()
                        ).await() // Using await for suspending behavior

                        Log.i(TAG, "Successfully sent progress for item ${item.id} to node $phoneNodeId.")
                        // Update needsSync to false
                        item.needsSync = false
                        db.downloadedItemDao().update(item)
                        Log.d(TAG, "Marked item ${item.id} as synced.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send progress for item ${item.id} to node $phoneNodeId: ${e.message}", e)
                        allItemsSuccessfullySent = false
                        // Decide if we should retry for this specific item or continue with others.
                        // For now, continue with others and the worker will retry the failed ones later.
                    }
                }

                if (allItemsSuccessfullySent) {
                    Log.i(TAG, "All items requiring sync were processed successfully.")
                    Result.success()
                } else {
                    Log.w(TAG, "Some items failed to sync. Will retry them in the next run.")
                    Result.retry() // Retry if any item failed to send
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during progress sync: ${e.message}", e)
                Result.failure(androidx.work.Data.Builder().putString("error", e.message).build())
            }
        }
    }

    private suspend fun getCapablePhoneNodeId(): String? {
        try {
            val capabilityInfo: CapabilityInfo = capabilityClient
                .getCapability(PHONE_APP_CAPABILITY_NAME, CapabilityClient.FILTER_REACHABLE)
                .await()

            // Pick the first connected node that has the capability.
            // You might have more sophisticated logic if multiple phones are somehow connected (unlikely for Wear OS).
            return capabilityInfo.nodes.firstOrNull()?.id
        } catch (e: Exception) {
            Log.e(TAG, "Error getting capable phone node: ${e.message}", e)
        }
        return null
    }
}
