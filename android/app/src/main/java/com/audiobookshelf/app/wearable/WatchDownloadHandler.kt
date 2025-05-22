package com.audiobookshelf.app.wearable

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.data.LocalLibraryItem
import com.audiobookshelf.app.device.DeviceManager
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

private const val TAG = "WatchDownloadHandler"

// Message paths for status updates to the watch
const val PATH_DOWNLOAD_STATUS_PREFIX = "/download/status/" // Append mediaItemId
const val PATH_DOWNLOAD_ASSET_PREFIX = "/download/asset/" // Append mediaItemId

// Status constants
const val STATUS_QUEUED = "QUEUED"
// const val STATUS_DOWNLOADING_TO_PHONE = "DOWNLOADING_TO_PHONE" // Future use
const val STATUS_PREPARING_ASSET = "PREPARING_ASSET"
const val STATUS_UPLOADING_TO_WATCH = "UPLOADING_TO_WATCH"
const val STATUS_FAILED = "FAILED"

// DataItem keys
const val KEY_MEDIA_ITEM_ID = "mediaItemId"
const val KEY_TITLE = "title"
const val KEY_AUTHOR = "author"
const val KEY_DURATION = "duration"
const val KEY_AUDIO_FILE = "audioFile"
const val KEY_COVER_IMAGE = "coverImage"


class WatchDownloadHandler(private val context: Context) {

    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val dataClient by lazy { Wearable.getDataClient(context) }
    private val nodeClient by lazy { Wearable.getNodeClient(context) }

    // Initialize DbManager - ensure it's initialized before use
    // DbManager.initialize(context) is typically called in Application's onCreate or MainActivity
    // For this handler, we assume it's already initialized.
    private val dbManager by lazy { DeviceManager.dbManager } // Assuming DeviceManager provides access

    fun handleDownloadRequestFromUI(mediaItemId: String) {
        Log.i(TAG, "UI requested download for mediaItemId: $mediaItemId")
        // Run the download process in a background coroutine
        CoroutineScope(Dispatchers.IO).launch {
            startDownloadToWatchInternal(mediaItemId)
        }
    }

    private suspend fun startDownloadToWatchInternal(mediaItemId: String) {
        Log.i(TAG, "startDownloadToWatchInternal called for mediaItemId: $mediaItemId")
        sendStatusUpdate(mediaItemId, STATUS_QUEUED, null)

        // 1. Fetch Item Metadata & Local Path
        val localLibraryItem: LocalLibraryItem? = try {
            dbManager.getLocalLibraryItemByLId(mediaItemId)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching LocalLibraryItem by LId $mediaItemId: ${e.message}", e)
            null
        }

        if (localLibraryItem == null) {
            Log.w(TAG, "Item $mediaItemId not found locally or error fetching.")
            sendStatusUpdate(mediaItemId, STATUS_FAILED, "Item not downloaded on phone.")
            return
        }

        val audioFileAbsolutePath: String? = localLibraryItem.localFiles.firstOrNull()?.absolutePath
        if (audioFileAbsolutePath.isNullOrEmpty()) {
            Log.w(TAG, "No local audio file path for $mediaItemId.")
            sendStatusUpdate(mediaItemId, STATUS_FAILED, "Audio file path not found.")
            return
        }

        sendStatusUpdate(mediaItemId, STATUS_PREPARING_ASSET, null)

        // 2. Prepare Audio Asset
        val audioFile = File(audioFileAbsolutePath)
        if (!audioFile.exists()) {
            Log.w(TAG, "Audio file does not exist at $audioFileAbsolutePath.")
            sendStatusUpdate(mediaItemId, STATUS_FAILED, "Audio file missing on phone.")
            return
        }

        val audioFileUri: Uri? = try {
            // IMPORTANT: Ensure 'com.audiobookshelf.app.fileprovider' matches manifest
            // and that 'file_paths.xml' allows access to the directory of audioFileAbsolutePath.
            // For now, assuming it's under context.getFilesDir() + "downloads/" based on file_paths.xml
            // This might need adjustment if files are stored elsewhere (e.g. getExternalFilesDir()).
            // If audioFileAbsolutePath is truly absolute and outside app's private files dir,
            // FileProvider setup needs to be more robust or direct file access (with permissions) used.
            // For this example, let's assume files are within getFilesDir()/downloads to match file_paths.xml
            // A more robust solution would be to copy to a shareable location if not already there.

            // This part is tricky. If localLibraryItem.localFiles.firstOrNull()?.absolutePath
            // is truly absolute like /storage/emulated/0/..., then the FileProvider path needs to be <external-path ... />
            // If it's relative to getFilesDir(), then <files-path ... /> is fine.
            // Let's try a generic File(path) approach first.
            FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", audioFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting URI for audio file $audioFileAbsolutePath: ${e.message}", e)
            null
        }

        if (audioFileUri == null) {
            sendStatusUpdate(mediaItemId, STATUS_FAILED, "Could not create URI for audio file.")
            return
        }
        val audioAsset = Asset.createFromUri(audioFileUri)

        // 3. Prepare Cover Asset (Optional)
        var coverAsset: Asset? = null
        localLibraryItem.coverAbsolutePath?.let { coverPath ->
            val coverFile = File(coverPath)
            if (coverFile.exists()) {
                try {
                    // Similar URI generation logic for cover
                    val coverFileUri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", coverFile)
                    coverAsset = Asset.createFromUri(coverFileUri)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not create asset from local cover $coverPath: ${e.message}")
                    // Fallback to downloading from URL if local fails or not present
                }
            }
        }

        // Fallback: If no local cover asset, try downloading from URL if available
        if (coverAsset == null && !localLibraryItem.media.metadata.coverUrl.isNullOrEmpty()) {
            Log.d(TAG, "Attempting to download cover from URL: ${localLibraryItem.media.metadata.coverUrl}")
            try {
                val bitmap = downloadBitmap(localLibraryItem.media.metadata.coverUrl!!)
                bitmap?.let {
                    val stream = ByteArrayOutputStream()
                    it.compress(Bitmap.CompressFormat.PNG, 80, stream)
                    coverAsset = Asset.createFromBytes(stream.toByteArray())
                    Log.d(TAG, "Successfully created cover asset from downloaded URL.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download or create cover asset from URL: ${e.message}")
            }
        }


        // 4. Send DataItem to Watch
        val dataPath = "$PATH_DOWNLOAD_ASSET_PREFIX$mediaItemId"
        val putDataMapReq = PutDataMapRequest.create(dataPath)
        putDataMapReq.dataMap.putString(KEY_MEDIA_ITEM_ID, mediaItemId)
        putDataMapReq.dataMap.putString(KEY_TITLE, localLibraryItem.media.metadata.title ?: "Unknown Title")
        putDataMapReq.dataMap.putString(KEY_AUTHOR, localLibraryItem.media.metadata.authorName ?: "Unknown Author")
        putDataMapReq.dataMap.putLong(KEY_DURATION, localLibraryItem.media.getDurationMillis()) // Assuming getDurationMillis exists
        putDataMapReq.dataMap.putAsset(KEY_AUDIO_FILE, audioAsset)
        coverAsset?.let { putDataMapReq.dataMap.putAsset(KEY_COVER_IMAGE, it) }
        // Add timestamp to ensure DataItem is always seen as new if re-sent
        putDataMapReq.dataMap.putLong("timestamp", System.currentTimeMillis())


        val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
        dataClient.putDataItem(putDataReq).addOnSuccessListener {
            Log.i(TAG, "Successfully sent DataItem for $mediaItemId to watch.")
            sendStatusUpdate(mediaItemId, STATUS_UPLOADING_TO_WATCH, null)
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to send DataItem for $mediaItemId: ${e.message}", e)
            sendStatusUpdate(mediaItemId, STATUS_FAILED, "Failed to send data to watch.")
        }
    }

    private suspend fun downloadBitmap(urlString: String): Bitmap? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000 // 10s
            connection.readTimeout = 15000 // 15s
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "downloadBitmap server returned HTTP ${connection.responseCode} ${connection.responseMessage} for URL $urlString")
                return@withContext null
            }
            inputStream = connection.inputStream
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading bitmap from $urlString: ${e.message}", e)
            null
        } finally {
            inputStream?.close()
            connection?.disconnect()
        }
    }


    private fun sendStatusUpdate(mediaItemId: String, status: String, reason: String?) {
        val statusPath = "$PATH_DOWNLOAD_STATUS_PREFIX$mediaItemId"
        val statusMessage = buildString {
            append("status=$status")
            reason?.let { append("&reason=${Uri.encode(it)}") } // URL encode reason
        }
        val statusData = statusMessage.toByteArray(StandardCharsets.UTF_8)

        nodeClient.connectedNodes.addOnSuccessListener { nodes ->
            if (nodes.isEmpty()) {
                Log.w(TAG, "No connected Wear OS nodes to send status update for $mediaItemId.")
                return@addOnSuccessListener
            }
            nodes.forEach { node ->
                messageClient.sendMessage(node.id, statusPath, statusData)
                    .addOnSuccessListener {
                        Log.d(TAG, "Sent status '$status' for $mediaItemId to node ${node.displayName} ($statusPath)")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Failed to send status '$status' for $mediaItemId to node ${node.displayName}: $e")
                    }
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to get connected nodes: $e")
        }
    }
}
