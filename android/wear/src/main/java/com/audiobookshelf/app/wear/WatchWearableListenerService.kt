package com.audiobookshelf.app.wear

import android.net.Uri
import android.util.Log
import com.audiobookshelf.app.wear.db.AppDatabase
import com.audiobookshelf.app.wear.db.DownloadedItem
import com.google.android.gms.wearable.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

private const val TAG = "WatchListenerService"

// Expected data paths and keys from the phone app
const val PATH_DOWNLOAD_ASSET_PREFIX = "/download/asset/"
const val PATH_DOWNLOAD_STATUS_PREFIX = "/download/status/" // For receiving status updates from phone
const val PATH_DOWNLOAD_ACK_PREFIX = "/download/ack/"     // For sending completion/failure ack to phone


const val KEY_MEDIA_ITEM_ID = "mediaItemId"
const val KEY_TITLE = "title"
const val KEY_AUTHOR = "author"
const val KEY_DURATION = "duration"
const val KEY_AUDIO_FILE = "audioFile"
const val KEY_COVER_IMAGE = "coverImage"


class WatchWearableListenerService : WearableListenerService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val db: AppDatabase by lazy { (application as WearApplication).database }
    private val messageClient: MessageClient by lazy { Wearable.getMessageClient(this) }


    override fun onDataChanged(dataEvents: DataEventBuffer) {
        Log.d(TAG, "onDataChanged received ${dataEvents.count} events")
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                val dataItem = event.dataItem
                if (dataItem.uri.path?.startsWith(PATH_DOWNLOAD_ASSET_PREFIX) == true) {
                    Log.i(TAG, "DataItem changed: ${dataItem.uri}")
                    processDataItem(dataItem)
                }
            } else if (event.type == DataEvent.TYPE_DELETED) {
                Log.i(TAG, "DataItem deleted: ${event.dataItem.uri}")
                // Optionally handle deleted data items, e.g., if a download was cancelled from phone
            }
        }
    }

    private fun processDataItem(dataItem: DataItem) {
        val dataMapItem = DataMapItem.fromDataItem(dataItem)
        val dataMap = dataMapItem.dataMap
        val path = dataItem.uri.path ?: ""
        val mediaItemId = path.substringAfter(PATH_DOWNLOAD_ASSET_PREFIX)

        if (mediaItemId.isEmpty()) {
            Log.e(TAG, "Could not extract mediaItemId from path: $path")
            return
        }

        Log.d(TAG, "Processing DataItem for mediaItemId: $mediaItemId")

        val title = dataMap.getString(KEY_TITLE, "Unknown Title")
        val author = dataMap.getString(KEY_AUTHOR, "Unknown Author")
        val duration = dataMap.getLong(KEY_DURATION, 0L)
        val audioAsset = dataMap.getAsset(KEY_AUDIO_FILE)
        val coverAsset = dataMap.getAsset(KEY_COVER_IMAGE) // Can be null

        if (audioAsset == null) {
            Log.e(TAG, "Audio asset is missing for mediaItemId: $mediaItemId")
            updateDownloadStatusInDb(mediaItemId, title, author, duration, null, null, "FAILED_NO_AUDIO_ASSET")
            sendAckToPhone(mediaItemId, "FAILED", "Audio asset missing")
            return
        }

        serviceScope.launch {
            val downloadedItem = DownloadedItem(
                id = mediaItemId,
                title = title,
                author = author,
                duration = duration,
                localAudioPath = null,
                localCoverPath = null,
                downloadStatus = "DOWNLOADING"
            )
            db.downloadedItemDao().insert(downloadedItem) // Initial insert with DOWNLOADING status

            var finalAudioPath: String? = null
            var finalCoverPath: String? = null
            var success = false

            try {
                // Save audio asset
                finalAudioPath = saveAssetToFile(mediaItemId, audioAsset, "audio.dat") // Use a generic extension or derive
                Log.i(TAG, "Audio asset saved to: $finalAudioPath for $mediaItemId")

                // Save cover asset (if present)
                if (coverAsset != null) {
                    finalCoverPath = saveAssetToFile(mediaItemId, coverAsset, "cover.jpg")
                    Log.i(TAG, "Cover asset saved to: $finalCoverPath for $mediaItemId")
                }
                success = true
            } catch (e: IOException) {
                Log.e(TAG, "IOException while saving assets for $mediaItemId: ${e.message}", e)
            }

            if (success && finalAudioPath != null) {
                val completedItem = downloadedItem.copy(
                    localAudioPath = finalAudioPath,
                    localCoverPath = finalCoverPath,
                    downloadStatus = "COMPLETED",
                    downloadedAt = System.currentTimeMillis()
                )
                db.downloadedItemDao().update(completedItem)
                Log.i(TAG, "Successfully downloaded and processed $mediaItemId")
                sendAckToPhone(mediaItemId, "COMPLETED", null)
            } else {
                val failedItem = downloadedItem.copy(
                    localAudioPath = finalAudioPath, // Might be partially saved or null
                    localCoverPath = finalCoverPath, // Might be partially saved or null
                    downloadStatus = "FAILED_SAVE_ASSET",
                    downloadedAt = System.currentTimeMillis()
                )
                db.downloadedItemDao().update(failedItem)
                Log.e(TAG, "Failed to save assets for $mediaItemId")
                sendAckToPhone(mediaItemId, "FAILED", "Could not save assets on watch")
            }
        }
    }

    @Throws(IOException::class)
    private suspend fun saveAssetToFile(mediaItemId: String, asset: Asset, fileName: String): String {
        val downloadsDir = File(filesDir, "downloads")
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        val itemDir = File(downloadsDir, mediaItemId)
        if (!itemDir.exists()) {
            itemDir.mkdirs()
        }
        val outputFile = File(itemDir, fileName)

        // It's crucial to use openAssetFileDescriptor and then get a FileInputStream.
        // Directly using Wearable.getDataClient().getFdForAsset(asset) can be problematic
        // as it needs to be managed carefully with threads and can time out.
        // The recommended way is to use an InputStream.

        val assetInputStream = Wearable.getDataClient(applicationContext).getFdForAsset(asset).await()?.inputStream
            ?: throw IOException("Failed to get InputStream for asset of $mediaItemId")

        FileOutputStream(outputFile).use { fileOutputStream ->
            assetInputStream.use { inputStream ->
                inputStream.copyTo(fileOutputStream)
            }
        }
        Log.d(TAG, "Asset saved: ${outputFile.absolutePath}")
        return outputFile.absolutePath
    }

    private fun updateDownloadStatusInDb(mediaItemId: String, title: String?, author: String?, duration: Long?,
                                         audioPath: String?, coverPath: String?, status: String) {
        serviceScope.launch {
            var item = db.downloadedItemDao().getById(mediaItemId)
            if (item == null) {
                item = DownloadedItem(
                    id = mediaItemId,
                    title = title,
                    author = author,
                    duration = duration,
                    localAudioPath = audioPath,
                    localCoverPath = coverPath,
                    downloadStatus = status,
                    downloadedAt = if (status == "COMPLETED" || status.startsWith("FAILED")) System.currentTimeMillis() else 0L
                )
                db.downloadedItemDao().insert(item)
            } else {
                item.localAudioPath = audioPath ?: item.localAudioPath
                item.localCoverPath = coverPath ?: item.localCoverPath
                item.downloadStatus = status
                if (status == "COMPLETED" || status.startsWith("FAILED")) {
                    item.downloadedAt = System.currentTimeMillis()
                }
                db.downloadedItemDao().update(item)
            }
        }
    }


    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "onMessageReceived: ${messageEvent.path}")
        val path = messageEvent.path
        if (path.startsWith(PATH_DOWNLOAD_STATUS_PREFIX)) {
            val mediaItemId = path.substringAfter(PATH_DOWNLOAD_STATUS_PREFIX)
            val data = String(messageEvent.data, StandardCharsets.UTF_8)
            Log.i(TAG, "Status update for $mediaItemId from phone: $data")

            // Parse status data (e.g., "status=QUEUED&reason=optional_message")
            val params = data.split("&").associate {
                val parts = it.split("=")
                parts[0] to (if (parts.size > 1) Uri.decode(parts[1]) else "")
            }
            val status = params["status"] ?: "UNKNOWN"
            // val reason = params["reason"] // Optional: use if needed

            serviceScope.launch {
                var item = db.downloadedItemDao().getById(mediaItemId)
                if (item == null) {
                    // If item doesn't exist, create a placeholder.
                    // Actual metadata will arrive with the DataItem.
                    item = DownloadedItem(id = mediaItemId, title = "Loading...", author = "", duration = 0L, downloadStatus = status)
                    db.downloadedItemDao().insert(item)
                } else {
                    // Only update status if it's not yet completed or failed on the watch side
                    if (item.downloadStatus != "COMPLETED" && !item.downloadStatus.startsWith("FAILED")) {
                        item.downloadStatus = status
                        db.downloadedItemDao().update(item)
                    }
                }
                Log.d(TAG, "Updated status for $mediaItemId to $status in DB.")
            }
        } else {
            super.onMessageReceived(messageEvent)
        }
    }

    private fun sendAckToPhone(mediaItemId: String, status: String, reason: String?) {
        val ackPath = "$PATH_DOWNLOAD_ACK_PREFIX$mediaItemId"
        val message = buildString {
            append("status=$status")
            reason?.let { append("&reason=${Uri.encode(it)}") }
        }
        val payload = message.toByteArray(StandardCharsets.UTF_8)

        Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isEmpty()) {
                Log.w(TAG, "No connected phone node to send ACK for $mediaItemId.")
                return@addOnSuccessListener
            }
            // Assuming the first connected node is the phone, or you might have specific node ID logic
            val phoneNodeId = nodes.first().id
            messageClient.sendMessage(phoneNodeId, ackPath, payload)
                .addOnSuccessListener { Log.i(TAG, "Sent ACK '$status' for $mediaItemId to node $phoneNodeId") }
                .addOnFailureListener { e -> Log.e(TAG, "Failed to send ACK for $mediaItemId to node $phoneNodeId: $e") }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to get connected nodes for ACK: $e")
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        Log.d(TAG, "WatchWearableListenerService destroyed")
    }
}
