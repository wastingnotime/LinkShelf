package org.wastingnotime.myfeed

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.wastingnotime.myfeed.preview.PreviewScheduler
import org.wastingnotime.myfeed.data.FeedDatabase
import org.wastingnotime.myfeed.data.LinkRepository

class FeedApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { FeedDatabase.open(this) }
    private val scheduler by lazy { PreviewScheduler(this) }
    val repository by lazy { LinkRepository(database.links(), enqueuePreview = { scheduler.enqueue(it) }) }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            // Recover a process death between the Room commit and WorkManager enqueue, including v1 rows.
            try { database.links().pendingPreviews().forEach { scheduler.enqueue(it, recover = true) } }
            catch (_: Exception) { /* a later app launch recovers the durable pending rows */ }
        }
    }
}
