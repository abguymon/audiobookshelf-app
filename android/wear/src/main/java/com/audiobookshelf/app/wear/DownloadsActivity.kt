package com.audiobookshelf.app.wear

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.activity.WearableActivity
import androidx.wear.widget.WearableRecyclerView
import com.audiobookshelf.app.wear.db.AppDatabase
import com.audiobookshelf.app.wear.db.DownloadedItem
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "DownloadsActivity"

class DownloadsActivity : WearableActivity() {

    private lateinit var downloadsRecyclerView: WearableRecyclerView
    private lateinit var downloadsAdapter: DownloadsAdapter
    private val db by lazy { (application as WearApplication).database }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)
        setAmbientEnabled()

        downloadsRecyclerView = findViewById(R.id.downloads_recycler_view)
        downloadsRecyclerView.isEdgeItemsCenteringEnabled = true
        // downloadsRecyclerView.layoutManager = WearableLinearLayoutManager(this) // Already set in XML

        downloadsAdapter = DownloadsAdapter(
            mutableListOf(),
            onItemClick = { item ->
                if (item.isDownloadComplete() && item.localAudioPath != null) {
                    val intent = Intent(this, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_LOCAL_MEDIA_ID, item.id) // Primary ID for local playback service
                        putExtra(PlayerActivity.EXTRA_MEDIA_TITLE, item.title)
                        //putExtra(PlayerActivity.EXTRA_MEDIA_ID, item.id) // Redundant if EXTRA_LOCAL_MEDIA_ID is used and handled
                        // No longer sending EXTRA_LOCAL_AUDIO_URI as the service will look it up
                    }
                    startActivity(intent)
                } else {
                    Log.w(TAG, "Item ${item.title} is not ready to play or path is null.")
                    // Optionally show a toast
                }
            },
            onItemDelete = { item ->
                showDeleteConfirmationDialog(item)
            }
        )
        downloadsRecyclerView.adapter = downloadsAdapter

        observeDownloads()
    }

    private fun observeDownloads() {
        db.downloadedItemDao().getAll().observe(this) { items ->
            Log.d(TAG, "Downloads updated: ${items.size} items")
            downloadsAdapter.updateItems(items)
        }
    }

    private fun showDeleteConfirmationDialog(item: DownloadedItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete Download")
            .setMessage("Are you sure you want to delete '${item.title}'?")
            .setPositiveButton("Delete") { dialog, _ ->
                deleteDownloadedItem(item)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun deleteDownloadedItem(item: DownloadedItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Delete files
                item.localAudioPath?.let { File(it).delete() }
                item.localCoverPath?.let { File(it).delete() }

                // Delete directory if empty (optional)
                item.localAudioPath?.let {
                    val parentDir = File(it).parentFile
                    if (parentDir?.isDirectory == true && parentDir.listFiles()?.isEmpty() == true) {
                        parentDir.delete()
                    }
                }
                db.downloadedItemDao().delete(item)
                Log.i(TAG, "Deleted item ${item.id} and its files.")
                withContext(Dispatchers.Main) {
                    // UI update will be handled by LiveData observation
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting item ${item.id}: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    // Show error toast or message
                }
            }
        }
    }
}

class DownloadsAdapter(
    private val items: MutableList<DownloadedItem>,
    private val onItemClick: (DownloadedItem) -> Unit,
    private val onItemDelete: (DownloadedItem) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val titleTextView: TextView = view.findViewById(R.id.item_title_text)
        val statusTextView: TextView = view.findViewById(R.id.item_status_text)
        val coverImageView: ImageView = view.findViewById(R.id.item_cover_image)
        val deleteButton: ImageButton = view.findViewById(R.id.item_delete_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.list_item_download, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.titleTextView.text = item.title ?: "Unknown Title"
        holder.statusTextView.text = "Status: ${item.downloadStatus}"

        if (item.isDownloadComplete()) {
            holder.deleteButton.visibility = View.VISIBLE
            holder.deleteButton.setOnClickListener { onItemDelete(item) }
            holder.statusTextView.visibility = View.GONE // Hide status if complete
        } else {
            holder.deleteButton.visibility = View.GONE
            holder.statusTextView.visibility = View.VISIBLE
        }

        item.localCoverPath?.let {
            Glide.with(holder.coverImageView.context)
                .load(File(it))
                .placeholder(R.mipmap.ic_launcher) // Placeholder while loading or if error
                .into(holder.coverImageView)
        } ?: holder.coverImageView.setImageResource(R.mipmap.ic_launcher) // Default if no cover

        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<DownloadedItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged() // Use DiffUtil for better performance in a real app
    }
}
