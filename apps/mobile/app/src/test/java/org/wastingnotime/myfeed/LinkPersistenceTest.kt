package org.wastingnotime.myfeed

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
import org.wastingnotime.myfeed.data.*
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

    @Test fun invalidPasteDoesNotWriteAndMeaningfulQueriesStayDistinct() = runBlocking {
        val repo = LinkRepository(db.links()) { 100 }
        assertNull(repo.capture("not a URL"))
        repo.capture("https://example.com/watch?v=a")
        repo.capture("https://example.com/watch?v=b")
        assertEquals(2, db.links().all().size)
    }
}
