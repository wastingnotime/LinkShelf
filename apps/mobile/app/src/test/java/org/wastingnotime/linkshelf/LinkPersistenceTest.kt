package org.wastingnotime.linkshelf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.wastingnotime.linkshelf.data.*
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LinkPersistenceTest {
    private lateinit var context: Context
    private lateinit var db: FeedDatabase
    private lateinit var name: String
    private val first = requireNotNull(UrlCapture.normalize("HTTPS://Example.com:443/story?q=1#original"))

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        name = "test-${UUID.randomUUID()}.db"
        db = FeedDatabase.open(context, name)
    }

    @After fun cleanup() { db.close(); context.deleteDatabase(name) }

    @Test fun favoritesPersistThroughReopenResharePreviewLifecycleAndUndo() = runBlocking {
        val dao = db.links()
        val saved = dao.capture(first, 100)
        assertFalse(saved.isFavorite)
        assertEquals(1, dao.toggleFavorite(saved.id))
        dao.finishPreview(saved.id, "Title", null, null, null, null, "ready")
        dao.markOpened(saved.id, 150)
        val undo = requireNotNull(dao.act(saved.id, LinkAction.Archive, 200))
        assertTrue(dao.find(saved.id)!!.isFavorite)
        dao.toggleFavorite(saved.id)
        assertTrue(dao.undo(undo))
        assertFalse(dao.find(saved.id)!!.isFavorite)
        dao.toggleFavorite(saved.id)
        dao.act(saved.id, LinkAction.Delete, 300)
        assertEquals(0, dao.toggleFavorite(saved.id))
        dao.capture(first, 400)
        db.close()
        db = FeedDatabase.open(context, name)
        val restored = requireNotNull(db.links().find(saved.id))
        assertTrue(restored.isFavorite)
        assertNull(restored.archivedAt)
        assertNull(restored.deletedAt)
        assertEquals(150L, restored.lastOpenedAt)
    }

    @Test fun migrationsKeepExistingLinksAndDefaultFavoritesToFalse() = runBlocking {
        for (version in listOf(1, 2)) {
            val fixture = "migration-$version-${UUID.randomUUID()}.db"
            val schema = org.json.JSONObject(java.io.File(
                "schemas/org.wastingnotime.linkshelf.data.FeedDatabase/$version.json").readText()).getJSONObject("database")
            context.openOrCreateDatabase(fixture, 0, null).use { sqlite ->
                val entity = schema.getJSONArray("entities").getJSONObject(0)
                sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", "saved_links"))
                val indices = entity.getJSONArray("indices")
                for (i in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", "saved_links"))
                sqlite.execSQL("INSERT INTO saved_links (id, originalUrl, canonicalUrl, host, capturedAt, archivedAt, title, previewStatus, revision) VALUES (1, 'https://example.com/', 'https://example.com/', 'example.com', 100, 200, 'Kept title', 'ready', 3)")
                sqlite.version = version
            }
            val migrated = FeedDatabase.open(context, fixture)
            try {
                val saved = requireNotNull(migrated.links().find(1))
                assertEquals("Kept title", saved.title)
                assertEquals(200L, saved.archivedAt)
                assertEquals(3L, saved.revision)
                assertFalse(saved.isFavorite)
                migrated.links().toggleFavorite(1)
                assertTrue(migrated.links().find(1)!!.isFavorite)
            } finally { migrated.close(); context.deleteDatabase(fixture) }
        }
    }

    @Test fun concurrentDuplicatesAcrossDatabaseConnectionsAreAtomic() = runBlocking {
        val initial = db.links().capture(first, 100)
        db.links().markOpened(initial.id, 150)
        db.links().act(initial.id, LinkAction.Archive, 200)
        db.links().act(initial.id, LinkAction.Delete, 250)
        val second = FeedDatabase.open(context, name)
        try {
            val captures = (1..40).map { n -> async(Dispatchers.Default) {
                val dao = if (n % 2 == 0) db.links() else second.links()
                dao.capture(requireNotNull(UrlCapture.normalize("https://example.com/story?q=1#copy$n")), 300L + n)
            } }.awaitAll()
            assertEquals(setOf(initial.id), captures.map { it.id }.toSet())
            val saved = db.links().all().single()
            assertEquals(340L, saved.capturedAt)
            assertEquals(first.original, saved.originalUrl)
            assertEquals(150L, saved.lastOpenedAt)
            assertNull(saved.archivedAt)
            assertNull(saved.deletedAt)
        } finally { second.close() }
    }

    @Test fun captureAndLifecycleSurviveDatabaseReopen() = runBlocking {
        val link = db.links().capture(first, 100)
        db.links().markOpened(link.id, 200)
        db.links().act(link.id, LinkAction.Archive, 300)
        db.close()
        db = FeedDatabase.open(context, name)
        val saved = db.links().all().single()
        assertEquals(link.id, saved.id)
        assertEquals(first.original, saved.originalUrl)
        assertEquals(300L, saved.archivedAt)
        assertEquals(200L, saved.lastOpenedAt)
        assertEquals(100L, saved.capturedAt)
    }

    @Test fun openingOnlyChangesLastOpenedAtInInboxAndArchive() = runBlocking {
        val link = db.links().capture(first, 100)
        db.links().markOpened(link.id, 200)
        assertEquals(link.copy(lastOpenedAt = 200), db.links().find(link.id))
        db.links().act(link.id, LinkAction.Archive, 300)
        val archived = requireNotNull(db.links().find(link.id))
        db.links().markOpened(link.id, 400)
        assertEquals(archived.copy(lastOpenedAt = 400), db.links().find(link.id))
    }

    @Test fun archiveDeleteAndUndoRestoreExactPreviousLifecycle() = runBlocking {
        val link = db.links().capture(first, 100)
        val archiveUndo = requireNotNull(db.links().act(link.id, LinkAction.Archive, 200))
        assertTrue(db.links().observeVisible().first().none { it.archivedAt == null })
        assertTrue(db.links().undo(archiveUndo))
        assertNull(db.links().find(link.id)?.archivedAt)
        db.links().act(link.id, LinkAction.Archive, 300)
        val deleteUndo = requireNotNull(db.links().act(link.id, LinkAction.Delete, 400))
        assertTrue(db.links().observeVisible().first().isEmpty())
        assertTrue(db.links().undo(deleteUndo))
        assertEquals(300L, db.links().find(link.id)?.archivedAt)
        assertNull(db.links().find(link.id)?.deletedAt)
    }

    @Test fun staleUndoCannotOverwriteReshareOrNewLifecycleAction() = runBlocking {
        val link = db.links().capture(first, 100)
        val token = requireNotNull(db.links().act(link.id, LinkAction.Archive, 200))
        db.links().capture(first, 300)
        assertFalse(db.links().undo(token))
        val delete = requireNotNull(db.links().act(link.id, LinkAction.Delete, 400))
        db.links().capture(first, 500)
        assertFalse(db.links().undo(delete))
        assertNull(db.links().find(link.id)?.archivedAt)
        assertNull(db.links().find(link.id)?.deletedAt)
    }

    @Test fun undoDoesNotEraseOpeningThatHappensAfterAction() = runBlocking {
        val link = db.links().capture(first, 100)
        val undo = requireNotNull(db.links().act(link.id, LinkAction.Archive, 200))
        db.links().markOpened(link.id, 300)
        assertTrue(db.links().undo(undo))
        assertEquals(300L, db.links().find(link.id)?.lastOpenedAt)
    }

    @Test fun newestFirstWithStableIdTieBreakAndDeletedRowsExcluded() = runBlocking {
        val older = db.links().capture(first, 100)
        val newer = db.links().capture(requireNotNull(UrlCapture.normalize("https://other.example/")), 200)
        val tied = db.links().capture(requireNotNull(UrlCapture.normalize("https://third.example/")), 200)
        assertEquals(listOf(tied.id, newer.id, older.id), db.links().observeVisible().first().map { it.id })
        db.links().act(newer.id, LinkAction.Delete, 300)
        assertEquals(listOf(tied.id, older.id), db.links().observeVisible().first().map { it.id })
        db.links().capture(first, 400)
        assertEquals(older.id, db.links().observeVisible().first().first().id)
    }

    @Test fun retentionIncludesExactBoundaryAndPreservesRecentRestoredAndArchivedLinks() = runBlocking {
        val dao = db.links()
        suspend fun link(path: String) = dao.capture(requireNotNull(UrlCapture.normalize("https://example.com/$path")), 1)
        val expired = link("expired")
        val recent = link("recent")
        val restored = link("restored")
        val archived = link("archived")
        dao.act(expired.id, LinkAction.Delete, 100)
        dao.act(recent.id, LinkAction.Delete, 101)
        dao.act(restored.id, LinkAction.Delete, 50)
        dao.restoreDeleted(restored.id)
        dao.act(archived.id, LinkAction.Archive, 50)
        val cleanup = DeletedLinkCleanup(context, dao)
        cleanup.purge(100 + DeletedLinkCleanup.RETENTION_MILLIS)
        assertNull(dao.find(expired.id))
        assertNotNull(dao.find(recent.id))
        assertNotNull(dao.find(restored.id))
        assertNotNull(dao.find(archived.id))
    }

    @Test fun permanentDeletionRemovesThumbnailRejectsUndoAndProtectsRestoredRows() = runBlocking {
        val dao = db.links()
        val link = dao.capture(first, 100)
        val token = requireNotNull(dao.act(link.id, LinkAction.Delete, 200))
        val image = java.io.File(context.filesDir, "previews/${link.id}.jpg")
        image.parentFile!!.mkdirs()
        image.writeText("thumbnail")
        val cleanup = DeletedLinkCleanup(context, dao)
        assertTrue(cleanup.permanentlyDelete(link.id))
        assertNull(dao.find(link.id))
        assertFalse(image.exists())
        assertFalse(dao.undo(token))
        val captured = dao.capture(first, 300)
        assertFalse(cleanup.permanentlyDelete(captured.id))
        dao.act(captured.id, LinkAction.Delete, 400)
        dao.restoreDeleted(captured.id)
        assertFalse(cleanup.permanentlyDelete(captured.id))
        assertNotNull(dao.find(captured.id))
    }

    @Test fun invalidPasteDoesNotWriteAndMeaningfulQueriesStayDistinct() = runBlocking {
        val repo = LinkRepository(db.links()) { 100 }
        assertNull(repo.capture("not a URL"))
        repo.capture("https://example.com/watch?v=a")
        repo.capture("https://example.com/watch?v=b")
        assertEquals(2, db.links().all().size)
    }
}
