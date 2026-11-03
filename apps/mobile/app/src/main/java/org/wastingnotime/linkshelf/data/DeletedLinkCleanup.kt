package org.wastingnotime.linkshelf.data

import android.content.Context
import androidx.work.*
import org.wastingnotime.linkshelf.FeedApplication
import org.wastingnotime.linkshelf.preview.PreviewImages
import java.util.concurrent.TimeUnit

/** Retention is measured from deletion, in elapsed 24-hour days. */
class DeletedLinkCleanup(private val context: Context, private val dao: LinkDao) {
    suspend fun purge(now: Long = System.currentTimeMillis()) {
        dao.purgeDeleted(now - RETENTION_MILLIS)
        removeOrphanedImages()
    }

    suspend fun permanentlyDelete(id: Long): Boolean {
        val removed = dao.permanentlyDelete(id) == 1
        removeOrphanedImages()
        return removed
    }

    private suspend fun removeOrphanedImages() {
        PreviewImages(context).removeOrphans { dao.find(it) != null }
    }

    companion object {
        const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("deleted-link-retention",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DeletedLinkCleanupWorker>(1, TimeUnit.DAYS).build())
        }
    }
}

class DeletedLinkCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val app = applicationContext as FeedApplication
        DeletedLinkCleanup(app, app.database.links()).purge()
        Result.success()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) { Result.retry() }
}
