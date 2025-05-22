package com.audiobookshelf.app.wear.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloaded_items")
data class DownloadedItem(
    @PrimaryKey val id: String, // This will be the mediaItemId from the phone
    val title: String?,
    val author: String?,
    val localAudioPath: String?, // Path to the saved audio file
    val localCoverPath: String?, // Path to the saved cover image
    val duration: Long?, // Duration in milliseconds
    var lastPlayedPosition: Long = 0,
    val downloadedAt: Long = System.currentTimeMillis(),
    var isFullyPlayed: Boolean = false,
    var needsSync: Boolean = false, // Added for progress sync
    // Optional: if you want to store the original server URL for reference
    // val serverUrl: String? = null,
    var downloadStatus: String = "PENDING" // e.g., PENDING, DOWNLOADING, COMPLETED, FAILED
) {
    // Helper to check if download was successful based on paths
    fun isDownloadComplete(): Boolean {
        return !localAudioPath.isNullOrEmpty() && downloadStatus == "COMPLETED"
    }
}
