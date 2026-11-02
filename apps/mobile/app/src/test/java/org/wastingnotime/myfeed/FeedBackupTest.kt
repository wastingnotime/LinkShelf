package org.wastingnotime.myfeed

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.wastingnotime.myfeed.data.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FeedBackupTest {
    @Test fun rejectsCoercionDuplicatesAndInvalidLateRowsWithoutPartialWrites() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = FeedDatabase.open(context, "backup-validation.db")
        try {
            val valid = org.json.JSONObject().put("url", "https://example.com/new").put("capturedAt", 100)
            fun backup(rows: org.json.JSONArray) = org.json.JSONObject().put("format", "my-feed").put("formatVersion", 1).put("links", rows)
            val root = backup(org.json.JSONArray().put(valid))
            for (bad in listOf(1.5, "100", -1, true, 1e30)) {
                val row = org.json.JSONObject(valid.toString()).put("capturedAt", bad)
                assertThrows(Exception::class.java) { FeedBackup.parse(backup(org.json.JSONArray().put(row)).toString()) }
            }
            assertThrows(Exception::class.java) { FeedBackup.parse(root.put("formatVersion", 1.9).toString()) }
            assertThrows(Exception::class.java) { FeedBackup.parse(backup(org.json.JSONArray().put(valid).put(valid)).toString()) }
            val invalid = backup(org.json.JSONArray().put(valid).put(org.json.JSONObject().put("url", "bad"))).toString()
            try { FeedBackup.import(db, invalid); fail("Must reject whole file") } catch (_: Exception) { }
            assertTrue(db.links().all().isEmpty())
            Unit
        } finally { db.close(); context.deleteDatabase("backup-validation.db") }
    }

    @Test fun roundTripPreservesLifecycleAndMergeKeepsExistingRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "backup-test.db"
        val db = FeedDatabase.open(context, name)
        try {
            val url = requireNotNull(UrlCapture.normalize("https://example.com/story"))
            val saved = db.links().capture(url, 100)
            db.links().act(saved.id, LinkAction.Archive, 200)
            db.links().markOpened(saved.id, 300)
            val raw = FeedBackup.export(db)
            val targetName = "backup-target.db"
            val target = FeedDatabase.open(context, targetName)
            try {
                assertEquals(1, FeedBackup.import(target, raw))
                assertEquals(200L, target.links().all().single().archivedAt)
                assertEquals("pending", target.links().all().single().previewStatus)
                try { FeedBackup.import(target, "{broken"); fail("Expected invalid backup") } catch (_: org.json.JSONException) { }
                assertEquals(1, target.links().all().size)
            } finally { target.close(); context.deleteDatabase(targetName) }
            val parsed = FeedBackup.parse(raw).single()
            assertEquals(200L, parsed.archivedAt)
            assertEquals(300L, parsed.lastOpenedAt)
            assertEquals(0, FeedBackup.import(db, raw))
            assertEquals(saved.id, db.links().all().single().id)
            assertThrows(Exception::class.java) { FeedBackup.parse(raw.replace("\"formatVersion\": 1", "\"formatVersion\": 9")) }
            assertThrows(Exception::class.java) { FeedBackup.parse("{broken") }
            Unit
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
