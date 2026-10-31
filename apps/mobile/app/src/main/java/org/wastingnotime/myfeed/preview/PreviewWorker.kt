package org.wastingnotime.myfeed.preview

import android.content.Context
import androidx.work.*
import org.wastingnotime.myfeed.FeedApplication
import java.util.concurrent.TimeUnit

class PreviewWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as FeedApplication
        val result = PreviewEnricher(app.database.links(), SafeHttp()::get, PreviewImages(app)::save)
            .enrich(inputData.getLong("linkId", -1), runAttemptCount)
        return if (result == PreviewOutcome.Retry) Result.retry() else Result.success()
    }
}

class PreviewScheduler(private val context: Context) {
    fun enqueue(id: Long, recover: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork("preview-$id",
            if (recover) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<PreviewWorker>()
                .setInputData(workDataOf("linkId" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build())
    }
}
