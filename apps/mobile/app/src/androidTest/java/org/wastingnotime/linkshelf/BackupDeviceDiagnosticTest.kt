package org.wastingnotime.linkshelf

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import androidx.room.withTransaction
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.wastingnotime.linkshelf.data.*

/** Explicit opt-in, read-only on the user's database. Restores only into a disposable database. */
@RunWith(AndroidJUnit4::class)
class BackupDeviceDiagnosticTest {
    @Test fun roundTripDeviceDataIntoIsolatedDatabase(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveBackup") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FeedApplication
        val name = "backup-diagnostic-${System.nanoTime()}.db"
        val target = FeedDatabase.open(app, name)
        try {
            val (source, raw) = app.database.withTransaction {
                app.database.links().all() to FeedBackup.export(app.database)
            }
            val exported = FeedBackup.parse(raw)
            assertEquals(source.map { it.copy(id = 0, imageUrl = null, imagePath = null, previewStatus = "pending", revision = 0) }.sortedBy { it.canonicalUrl },
                exported.sortedBy { it.canonicalUrl })
            assertEquals(exported.size, FeedBackup.import(target, raw))
            val restored = target.links().all().map { it.copy(id = 0) }.sortedBy { it.canonicalUrl }
            assertEquals(exported.sortedBy { it.canonicalUrl }, restored)
            assertEquals(0, FeedBackup.import(target, raw))
            val before = target.links().all()
            val broken = org.json.JSONObject(raw)
            broken.getJSONArray("links").put(org.json.JSONObject().put("url", "not a link"))
            try { FeedBackup.import(target, broken.toString()); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
            assertEquals(before, target.links().all())
            android.util.Log.i("MyFeedDiagnostic", "Backup round trip: ${exported.size} records; duplicate import skipped; invalid import preserved database")
            Unit
        } finally { target.close(); app.deleteDatabase(name) }
    }
}
