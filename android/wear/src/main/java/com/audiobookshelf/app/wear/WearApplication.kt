package com.audiobookshelf.app.wear

import android.app.Application
import com.audiobookshelf.app.wear.db.AppDatabase

class WearApplication : Application() {
    // Using by lazy so the database and the repository are only created when they're needed
    // rather than when the application starts
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        initializeSyncWorker()
    }

    private fun initializeSyncWorker() {
        val workManager = androidx.work.WorkManager.getInstance(applicationContext)

        // Define constraints for the worker (e.g., network connected)
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()

        // Create a periodic work request
        // Sync every 6 hours, for example. Adjust interval as needed.
        val periodicSyncRequest =
            androidx.work.PeriodicWorkRequestBuilder<com.audiobookshelf.app.wear.sync.ProgressSyncWorker>(6, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

        workManager.enqueueUniquePeriodicWork(
            com.audiobookshelf.app.wear.sync.ProgressSyncWorker.WORKER_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP, // Keep existing if already scheduled
            periodicSyncRequest
        )

        // Also, enqueue a one-time request on app startup to sync if needed soon
        val oneTimeSyncRequest = androidx.work.OneTimeWorkRequestBuilder<com.audiobookshelf.app.wear.sync.ProgressSyncWorker>()
            .setConstraints(constraints) // Use same constraints
            // Optionally, add an initial delay if you don't want it to run immediately
            // .setInitialDelay(1, java.util.concurrent.TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniqueWork(
            "${com.audiobookshelf.app.wear.sync.ProgressSyncWorker.WORKER_NAME}_OneTime",
            androidx.work.ExistingWorkPolicy.KEEP, // Keep if already running or completed recently
            oneTimeSyncRequest
        )
        android.util.Log.d("WearApplication", "ProgressSyncWorker scheduled.")
    }
}
