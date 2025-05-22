package com.audiobookshelf.app.wear.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface DownloadedItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: DownloadedItem)

    @Update
    suspend fun update(item: DownloadedItem)

    @Delete
    suspend fun delete(item: DownloadedItem)

    @Query("SELECT * FROM downloaded_items WHERE id = :id")
    suspend fun getById(id: String): DownloadedItem?

    @Query("SELECT * FROM downloaded_items WHERE id = :id")
    fun getByIdBlocking(id: String): DownloadedItem? // Non-suspending version

    @Query("SELECT * FROM downloaded_items ORDER BY downloadedAt DESC")
    fun getAll(): LiveData<List<DownloadedItem>> // Use LiveData for reactive UI

    @Query("SELECT * FROM downloaded_items ORDER BY downloadedAt DESC")
    suspend fun getAllBlocking(): List<DownloadedItem> // For non-UI blocking access if needed

    @Query("SELECT * FROM downloaded_items WHERE needsSync = 1") // Query for items needing sync
    suspend fun getItemsNeedingSync(): List<DownloadedItem>
}
