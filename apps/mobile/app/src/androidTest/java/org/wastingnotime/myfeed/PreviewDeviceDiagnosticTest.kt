package org.wastingnotime.myfeed

import android.content.ContextWrapper
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.wastingnotime.myfeed.data.FeedDatabase
import org.wastingnotime.myfeed.preview.*
import java.io.File

/** Opt-in live diagnostic. Uses a separate database/cache; never clears the user's inbox. */
@RunWith(AndroidJUnit4::class)
class PreviewDeviceDiagnosticTest {
    @Test fun investigateSavedLinks() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("livePreview") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FeedApplication
        val rows = app.database.links().all().filter { it.deletedAt == null && it.imagePath == null }
        val dir = File(app.cacheDir, "preview-diagnostic-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(app) { override fun getFilesDir() = dir }
        val name = "preview-diagnostic-${System.nanoTime()}.db"
        val db = FeedDatabase.open(app, name)
        try {
            val enricher = PreviewEnricher(db.links(), SafeHttp()::get, PreviewImages(context)::save)
            val successes = mutableListOf<Long>()
            for (row in rows) {
                val id = db.links().insert(row.copy(id = 0, previewStatus = "pending"))
                enricher.enrich(id, 3)
                val result = requireNotNull(db.links().find(id))
                Log.i("MyFeedDiagnostic", "id=${row.id} host=${row.host} status=${result.previewStatus} title=${result.title != null} image=${result.imagePath != null}")
                if (result.imagePath != null) successes += row.id
            }
            if (args.getString("refreshSuccessful") == "true") {
                successes.forEach { app.repository.retryPreview(it) }
                withTimeout(120000) {
                    while (successes.any { app.database.links().find(it)?.previewStatus == "pending" }) delay(500)
                }
                for (id in successes) assertNotNull("Saved thumbnail for $id", app.database.links().find(id)?.imagePath)
            }
            for (row in rows) {
                val current = requireNotNull(app.database.links().find(row.id))
                assertEquals(row.originalUrl, current.originalUrl)
                assertEquals(row.capturedAt, current.capturedAt)
                assertEquals(row.archivedAt, current.archivedAt)
                assertEquals(row.deletedAt, current.deletedAt)
                assertEquals(row.lastOpenedAt, current.lastOpenedAt)
            }
        } finally { db.close(); app.deleteDatabase(name); dir.deleteRecursively() }
    }
}
