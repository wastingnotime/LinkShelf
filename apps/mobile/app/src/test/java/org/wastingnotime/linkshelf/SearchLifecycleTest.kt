package org.wastingnotime.linkshelf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class SearchLifecycleTest {
    private lateinit var context: Context
    private lateinit var db: FeedDatabase
    private val name = "search-${UUID.randomUUID()}.db"
    private var now = 100L
    private val dao get() = db.links()
    private val repo get() = LinkRepository(dao, now = { now++ })
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); db = FeedDatabase.open(context, name) }
    @After fun cleanup() { db.close(); context.deleteDatabase(name) }

    private suspend fun results(query: String, scope: SearchScope): List<SavedLink> = withTimeout(5_000) {
        FeedProjection.select(repo.links.first(), scope, query)
    }

    @Test fun captureEnrichOpenArchiveRestoreDeleteUndoReshareAndReopenStaySearchable() = runBlocking {
        val first = requireNotNull(repo.capture("https://example.com/needle"))
        val second = requireNotNull(repo.capture("https://example.com/other"))
        assertEquals(listOf(first.id), results("needle", SearchScope.Inbox).map { it.id })
        assertTrue(results("cafe", SearchScope.All).isEmpty())
        dao.finishPreview(first.id, "Café 東京", "A searchable description", null, null, null, "ready")
        assertEquals(listOf(first.id), results("cafe", SearchScope.Inbox).map { it.id })
        assertEquals(listOf(first.id), results("searchable", SearchScope.All).map { it.id })
        repo.markOpened(first.id)
        val opened = results("東京", SearchScope.Inbox).single()
        assertNotNull(opened.lastOpenedAt)
        assertEquals(first.capturedAt, opened.capturedAt)
        val archiveUndo = requireNotNull(repo.act(first.id, LinkAction.Archive))
        assertTrue(results("cafe", SearchScope.Inbox).isEmpty())
        assertEquals(listOf(first.id), results("cafe", SearchScope.Archived).map { it.id })
        assertEquals(listOf(second.id, first.id), results("", SearchScope.All).map { it.id })
        assertTrue(repo.undo(archiveUndo))
        assertTrue(results("cafe", SearchScope.Archived).isEmpty())
        repo.act(first.id, LinkAction.Archive)
        repo.act(first.id, LinkAction.Restore)
        assertEquals(first.id, results("cafe", SearchScope.Inbox).single().id)
        repo.act(first.id, LinkAction.Archive)
        val deleteUndo = requireNotNull(repo.act(first.id, LinkAction.Delete))
        SearchScope.entries.forEach { assertTrue(results("needle", it).isEmpty()) }
        assertTrue(repo.undo(deleteUndo))
        assertEquals(first.id, results("cafe", SearchScope.Archived).single().id)
        repo.act(first.id, LinkAction.Delete)
        val restored = requireNotNull(repo.capture("https://example.com/needle#again"))
        assertEquals(first.id, restored.id)
        assertEquals(listOf(first.id, second.id), results("", SearchScope.Inbox).map { it.id })
        assertEquals(opened.lastOpenedAt, restored.lastOpenedAt)
        assertTrue(results("", SearchScope.Archived).isEmpty())
        db.close(); db = FeedDatabase.open(context, name)
        assertEquals(first.id, results("cafe", SearchScope.Inbox).single().id)
        val inbox = results("", SearchScope.Inbox)
        assertEquals(inbox.map { it.id }.toSet(), FeedProjection.group(inbox).flatMap { it.links }.map { it.id }.toSet())
    }

    @Test fun failedOrPendingPreviewSearchNeedsNoMetadataAndDeletedRowsStayExcludedAfterReopen() = runBlocking {
        val row = requireNotNull(repo.capture("https://example.com/offline"))
        assertEquals(row.id, results("offline", SearchScope.All).single().id)
        dao.finishPreview(row.id, null, null, null, null, null, "failed")
        assertEquals(row.id, results("offline", SearchScope.Inbox).single().id)
        repo.act(row.id, LinkAction.Delete)
        db.close(); db = FeedDatabase.open(context, name)
        assertEquals(1, dao.all().size)
        SearchScope.entries.forEach { assertTrue(results("", it).isEmpty()) }
    }
}
