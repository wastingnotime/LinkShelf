package org.wastingnotime.linkshelf

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.wastingnotime.linkshelf.preview.PreviewScheduler
import org.wastingnotime.linkshelf.data.FeedDatabase
import org.wastingnotime.linkshelf.data.LinkRepository

class FeedApplication : Application(), androidx.work.Configuration.Provider {
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { FeedDatabase.open(this) }
    private val scheduler by lazy { PreviewScheduler(this) }
    val repository by lazy { LinkRepository(database.links(), enqueuePreview = { scheduler.enqueue(it) }) }

    override fun onCreate() {
        super.onCreate()
        org.wastingnotime.linkshelf.ui.syncLaunchAppearance(this,
            getSharedPreferences("appearance", 0).getString("theme", "System") ?: "System")
        org.wastingnotime.linkshelf.data.ScheduledBackup(this).ensureScheduled()
        org.wastingnotime.linkshelf.data.DeletedLinkCleanup.schedule(this)
        applicationScope.launch {
            runCatching { org.wastingnotime.linkshelf.data.DeletedLinkCleanup(this@FeedApplication, database.links()).purge() }
            // Recover a process death between the Room commit and WorkManager enqueue, including v1 rows.
            try { database.links().pendingPreviews().forEach { scheduler.enqueue(it, recover = true) } }
            catch (_: Exception) { /* a later app launch recovers the durable pending rows */ }
        }
    }
}
