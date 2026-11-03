package org.wastingnotime.linkshelf

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.wastingnotime.linkshelf.data.*
import org.wastingnotime.linkshelf.preview.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewRecoveryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: FeedDatabase
    private lateinit var name: String
    private val url = requireNotNull(UrlCapture.normalize("https://example.com/story#original"))
    @Before fun setup() { name = "preview-${UUID.randomUUID()}.db"; db = FeedDatabase.open(context, name) }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private fun page(text: String) = Download("https://example.com/final".toHttpUrl(), text.toByteArray(), "text/html")

    @Test fun emptyRefreshKeepsImportedTextAndSavedCache() = runBlocking {
        val saved = db.links().capture(url, 100)
        db.links().finishPreview(saved.id, "Imported title", "Imported description", null, null, "existing.jpg", "pending")
        val enricher = PreviewEnricher(db.links(), { _, _ -> page("<html></html>") }, { _, _ -> null })
        enricher.enrich(saved.id, 3)
        val refreshed = requireNotNull(db.links().find(saved.id))
        assertEquals("Imported title", refreshed.title)
        assertEquals("Imported description", refreshed.description)
        assertEquals("existing.jpg", refreshed.imagePath)
    }

    @Test fun committedOfflineCaptureSurvivesSchedulerFailureAndDatabaseReopen() = runBlocking {
        val repo = LinkRepository(db.links(), enqueuePreview = { throw IllegalStateException("scheduler unavailable") }) { 100 }
        val saved = requireNotNull(repo.capture(url.original))
        assertEquals("pending", saved.previewStatus)
        db.close(); db = FeedDatabase.open(context, name)
        assertEquals(listOf(saved.id), db.links().pendingPreviews())
        val enricher = PreviewEnricher(db.links(), { _, _ -> page("<title>Recovered</title>") }, { _, _ -> null })
        assertEquals(PreviewOutcome.Done, enricher.enrich(saved.id, 0))
        assertEquals(saved.copy(title = "Recovered", previewStatus = "ready"), db.links().find(saved.id))
    }

    @Test fun networkRetryThenRecoveryPreservesLifecycleOpeningOrderingIdentityAndUndo() = runBlocking {
        val saved = db.links().capture(url, 100)
        val token = requireNotNull(db.links().act(saved.id, LinkAction.Archive, 200))
        db.links().markOpened(saved.id, 300)
        var requests = 0
        val enricher = PreviewEnricher(db.links(), { _, _ ->
            if (requests++ == 0) throw IOException("offline")
            page("<meta property=og:title content=Recovered><meta name=description content=Description>")
        }, { _, _ -> null })
        assertEquals(PreviewOutcome.Retry, enricher.enrich(saved.id, 0))
        assertEquals("pending", db.links().find(saved.id)?.previewStatus)
        assertEquals(PreviewOutcome.Done, enricher.enrich(saved.id, 1))
        assertEquals(saved.copy(title = "Recovered", description = "Description", previewStatus = "ready",
            archivedAt = 200, lastOpenedAt = 300, revision = 1), db.links().find(saved.id))
        assertTrue(db.links().undo(token))
        assertNull(db.links().find(saved.id)?.archivedAt)
    }

    @Test fun finiteRetriesBecomeUsefulFallbackThenManualRetryAndReshareRecover() = runBlocking {
        val saved = db.links().capture(url, 100)
        val unavailable = PreviewEnricher(db.links(), { _, _ -> throw IOException("offline") }, { _, _ -> null })
        repeat(3) { assertEquals(PreviewOutcome.Retry, unavailable.enrich(saved.id, it)) }
        assertEquals(PreviewOutcome.Done, unavailable.enrich(saved.id, 3))
        assertEquals(saved.copy(previewStatus = "failed:Page network request failed"), db.links().find(saved.id))
        val scheduled = mutableListOf<Long>()
        val repo = LinkRepository(db.links(), enqueuePreview = { scheduled += it }) { 200 }
        repo.retryPreview(saved.id)
        assertEquals(listOf(saved.id), scheduled)
        assertEquals("pending", db.links().find(saved.id)?.previewStatus)
        val denied = PreviewEnricher(db.links(), { _, _ -> throw UnsafePreview("private") }, { _, _ -> null })
        assertEquals(PreviewOutcome.Done, denied.enrich(saved.id, 0))
        assertEquals("failed:Page: private", db.links().find(saved.id)?.previewStatus)
        val again = requireNotNull(repo.capture(url.original))
        assertEquals(saved.id, again.id); assertEquals("pending", again.previewStatus)
        assertEquals(listOf(saved.id, saved.id), scheduled)
    }

    @Test fun deletedAndReadyRowsAreSkippedAndEmptyHtmlFailsWithoutLosingUrl() = runBlocking {
        val saved = db.links().capture(url, 100)
        var requests = 0
        val enricher = PreviewEnricher(db.links(), { _, _ -> requests++; page("<script>evil()</script>") }, { _, _ -> null })
        db.links().act(saved.id, LinkAction.Delete, 200)
        assertEquals(PreviewOutcome.Done, enricher.enrich(saved.id, 0)); assertEquals(0, requests)
        db.links().capture(url, 300)
        enricher.enrich(saved.id, 0)
        assertEquals("failed:No preview metadata found", db.links().find(saved.id)?.previewStatus)
        assertEquals(url.original, db.links().find(saved.id)?.originalUrl)
        db.links().retryPreview(saved.id)
        val ready = PreviewEnricher(db.links(), { _, _ -> requests++; page("<title>Ready</title>") }, { _, _ -> null })
        ready.enrich(saved.id, 0); ready.enrich(saved.id, 0)
        assertEquals(2, requests)
    }

    @Test fun imageFailurePreservesTextRetriesTransientFailureAndUsesFiniteTextFallback() = runBlocking {
        val saved = db.links().capture(url, 100)
        val enricher = PreviewEnricher(db.links(), { _, image ->
            if (image) throw IOException("image temporarily unavailable")
            page("<meta property=og:title content=Article><meta property=og:image content='/card.png'>")
        }, { _, _ -> null })
        assertEquals(PreviewOutcome.Retry, enricher.enrich(saved.id, 0))
        assertEquals("Article", db.links().find(saved.id)?.title)
        assertEquals("pending", db.links().find(saved.id)?.previewStatus)
        assertEquals(PreviewOutcome.Done, enricher.enrich(saved.id, 3))
        assertEquals("partial:Image network request failed", db.links().find(saved.id)?.previewStatus)
        assertNull(db.links().find(saved.id)?.imagePath)
        assertEquals(1, db.links().retryPreview(saved.id))
        assertEquals("pending", db.links().find(saved.id)?.previewStatus)
    }

    @Test fun imageSafetyFailureKeepsTextAndLaterNetworkExhaustionKeepsPriorMetadata() = runBlocking {
        val saved = db.links().capture(url, 100)
        val deniedImage = PreviewEnricher(db.links(), { _, image ->
            if (image) throw UnsafePreview("image redirected to private address")
            page("<title>Article</title><meta property=og:image content='/card.png'>")
        }, { _, _ -> null })
        assertEquals(PreviewOutcome.Done, deniedImage.enrich(saved.id, 0))
        assertEquals("partial:Image: image redirected to private address", db.links().find(saved.id)?.previewStatus)
        assertEquals("Article", db.links().find(saved.id)?.title)
        assertNull(db.links().find(saved.id)?.imagePath)
        // Simulate text already saved during an earlier image retry, then an unavailable page.
        db.links().finishPreview(saved.id, null, null, null, null, null, "pending") // ready rows ignore updates
        assertEquals("partial:Image: image redirected to private address", db.links().find(saved.id)?.previewStatus)
        val second = db.links().capture(requireNotNull(UrlCapture.normalize("https://example.com/retry")), 200)
        db.links().finishPreview(second.id, "Previous useful title", null, null, null, null, "pending")
        val offline = PreviewEnricher(db.links(), { _, _ -> throw IOException("offline") }, { _, _ -> null })
        assertEquals(PreviewOutcome.Done, offline.enrich(second.id, 3))
        assertEquals("Previous useful title", db.links().find(second.id)?.title)
        assertEquals("partial:Page network request failed", db.links().find(second.id)?.previewStatus)
    }

    @Test fun undoReschedulesPreviewSkippedWhileDeleted() = runBlocking {
        val scheduled = mutableListOf<Long>()
        val repo = LinkRepository(db.links(), enqueuePreview = { scheduled += it }) { 100 }
        val saved = requireNotNull(repo.capture(url.original))
        val undo = requireNotNull(repo.act(saved.id, LinkAction.Delete))
        var requests = 0
        val enricher = PreviewEnricher(db.links(), { _, _ -> requests++; page("<title>Recovered</title>") }, { _, _ -> null })
        enricher.enrich(saved.id, 0)
        assertEquals(0, requests)
        assertTrue(repo.undo(undo))
        assertEquals(listOf(saved.id, saved.id), scheduled)
        enricher.enrich(saved.id, 0)
        assertEquals("ready", db.links().find(saved.id)?.previewStatus)
    }

    @Test fun deletionDuringDownloadPreventsLateMetadataWrite() = runBlocking {
        val saved = db.links().capture(url, 100)
        val enricher = PreviewEnricher(db.links(), { _, _ ->
            runBlocking { db.links().act(saved.id, LinkAction.Delete, 200) }
            page("<title>Late result</title>")
        }, { _, _ -> null })
        enricher.enrich(saved.id, 0)
        val deleted = requireNotNull(db.links().find(saved.id))
        assertEquals(200L, deleted.deletedAt); assertNull(deleted.title)
        assertEquals(1L, deleted.revision)
    }

    @Test fun validImageIsCachedAndMalformedOversizedImagesAreRejected() = runBlocking {
        val saved = db.links().capture(url, 100)
        val bytes = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888).also {
                it.compress(Bitmap.CompressFormat.PNG, 100, out); it.recycle()
            }
        }.toByteArray()
        val images = PreviewImages(context)
        val enricher = PreviewEnricher(db.links(), { _, image ->
            if (image) Download("https://example.com/card.png".toHttpUrl(), bytes, "image/png")
            else page("<meta property=og:title content=Article><meta property=og:image content='/card.png'>")
        }, images::save)
        assertEquals(PreviewOutcome.Done, enricher.enrich(saved.id, 0))
        val path = requireNotNull(db.links().find(saved.id)?.imagePath)
        val cached = requireNotNull(images.load(path))
        assertTrue(cached.width <= 1024); assertTrue(cached.height <= 1024); cached.recycle()
        assertNull(images.load("../../secret"))
        assertNull(images.save(999, "<svg onload='evil()'/>".toByteArray()))
        assertNull(images.save(999, ByteArray(SafeHttp.IMAGE_BYTES + 1)))
        val oversized = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(4097, 1, Bitmap.Config.ARGB_8888).also {
                it.compress(Bitmap.CompressFormat.PNG, 100, out); it.recycle()
            }
        }.toByteArray()
        assertNull(images.save(999, oversized))
    }

    @Test fun versionOneMigrationPreservesDataAndMakesFallbackRowsPending() = runBlocking {
        db.close()
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("""CREATE TABLE saved_links (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                originalUrl TEXT NOT NULL, canonicalUrl TEXT NOT NULL, host TEXT NOT NULL, capturedAt INTEGER NOT NULL,
                archivedAt INTEGER, deletedAt INTEGER, lastOpenedAt INTEGER, title TEXT, description TEXT,
                imageUrl TEXT, siteName TEXT, previewStatus TEXT NOT NULL, revision INTEGER NOT NULL)""")
            old.execSQL("CREATE UNIQUE INDEX index_saved_links_canonicalUrl ON saved_links (canonicalUrl)")
            old.execSQL("""INSERT INTO saved_links VALUES (1, 'https://example.com/story#original',
                'https://example.com/story', 'example.com', 100, 200, NULL, 300, NULL, NULL, NULL, NULL, 'fallback', 7)""")
            old.version = 1
        }
        db = FeedDatabase.open(context, name)
        assertEquals(SavedLink(id = 1, originalUrl = url.original, canonicalUrl = url.canonical, host = "example.com",
            capturedAt = 100, archivedAt = 200, lastOpenedAt = 300, revision = 7), db.links().all().single())
    }

    @Test fun actualWorkerBlocksPrivateDestinationsAndMarksFailed() = runBlocking {
        val app = context as FeedApplication
        val saved = app.database.links().capture(requireNotNull(UrlCapture.normalize("http://127.0.0.1/private")), 100)
        val worker = TestListenableWorkerBuilder<PreviewWorker>(context)
            .setInputData(workDataOf("linkId" to saved.id)).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals("failed:Page: Non-public IP", app.database.links().find(saved.id)?.previewStatus)
        withContext(Dispatchers.IO) { app.database.clearAllTables() }
    }
}
