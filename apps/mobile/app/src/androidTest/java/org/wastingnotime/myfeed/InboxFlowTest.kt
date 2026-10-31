package org.wastingnotime.myfeed

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.room.withTransaction
import androidx.work.WorkManager
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.Espresso
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.wastingnotime.myfeed.data.LinkAction
import org.wastingnotime.myfeed.data.SavedLink

@RunWith(AndroidJUnit4::class)
class InboxFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as FeedApplication
    private val dao get() = app.database.links()

    @Before fun clearInbox() = runBlocking(Dispatchers.IO) {
        WorkManager.getInstance(app).cancelAllWork().result.get()
        // Deterministic UI/lifecycle tests: no external requests, regardless of CI connectivity.
        // Production scheduling and network recovery are exercised by runs/WNT-220-smoke.py.
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
        app.database.clearAllTables()
    }

    private fun rows(): List<SavedLink> = runBlocking { dao.all() }
    private fun awaitRows(check: (List<SavedLink>) -> Boolean) {
        compose.waitUntil(10_000) { check(rows()) }
        compose.waitForIdle()
    }

    private fun send(text: String): ActivityScenario<ShareActivity> = ActivityScenario.launch(
        Intent(app, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })

    @Test fun pasteRecreateOpenArchiveDeleteUndoAndRestore() {
        val original = "HTTPS://Example.com:443/article?q=one#original"
        val openedUrl = "https://Example.com:443/article?q=one#original"
        compose.onNodeWithText("Paste link").performClick()
        compose.onNodeWithText("URL or shared text").performTextInput(original)
        compose.onNodeWithText("Save link").performClick()
        awaitRows { it.size == 1 }
        val captured = rows().single()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(original).assertIsDisplayed()
        assertEquals(captured, rows().single())

        Intents.init()
        try {
            Intents.intending(hasAction(Intent.ACTION_VIEW)).respondWith(ActivityResult(Activity.RESULT_OK, null))
            compose.onNodeWithText(original).performClick()
            awaitRows { it.single().lastOpenedAt != null }
            Intents.intended(allOf(hasAction(Intent.ACTION_VIEW), hasData(openedUrl)))
            assertEquals(captured.copy(lastOpenedAt = rows().single().lastOpenedAt), rows().single())
        } finally { Intents.release() }

        compose.onNode(hasText("Archive") and hasClickAction()).performClick()
        awaitRows { it.single().archivedAt != null }
        compose.onNodeWithText(original).assertDoesNotExist()
        compose.onNodeWithText("Archived").performClick()
        compose.onNodeWithText(original).assertIsDisplayed()
        val archivedAt = rows().single().archivedAt
        // Dismiss the archive snackbar so the next Undo belongs to deletion.
        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        awaitRows { it.single().deletedAt != null }
        compose.onNodeWithText(original).assertDoesNotExist()
        compose.onNodeWithText("Undo").performClick()
        awaitRows { it.single().deletedAt == null }
        assertEquals(archivedAt, rows().single().archivedAt)
        compose.onNode(hasText("Restore") and hasClickAction()).performClick()
        awaitRows { it.single().archivedAt == null }
        compose.onNodeWithText("Grouped").performClick()
        compose.onNodeWithTag("group-${captured.id}").assertIsDisplayed()
        compose.onNodeWithText(original).assertIsDisplayed()
    }

    @Test fun receiverRecreationDuringCommitDoesNotCaptureTwice() {
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = app.applicationScope.launch {
            app.database.withTransaction { locked.complete(Unit); release.await() }
        }
        runBlocking { locked.await() }
        try {
            send("Shared from another app https://example.com/rotation").use { receiver ->
                receiver.recreate()
                release.complete(Unit)
                awaitRows { it.size == 1 }
                assertEquals(0L, rows().single().revision)
                compose.waitUntil(10_000) { receiver.state == Lifecycle.State.DESTROYED }
            }
        } finally {
            release.complete(Unit)
            runBlocking { blocker.join() }
        }
    }

    @Test fun repeatedSharesRestoreInboxPreserveOriginalAndReturnWithoutConfirmation() {
        val original = "HTTPS://Example.com:443/shared#one"
        send("Check this $original").use { receiver ->
            awaitRows { it.size == 1 }
            compose.waitUntil(10_000) { receiver.state == Lifecycle.State.DESTROYED }
        }
        val first = rows().single()
        runBlocking {
            dao.markOpened(first.id, 123)
            dao.act(first.id, LinkAction.Archive, 456)
            dao.act(first.id, LinkAction.Delete, 789)
        }
        send("https://example.com/shared#two").use { receiver ->
            awaitRows { it.single().deletedAt == null }
            compose.waitUntil(10_000) { receiver.state == Lifecycle.State.DESTROYED }
        }
        val again = rows().single()
        assertEquals(first.id, again.id)
        assertEquals(original, again.originalUrl)
        assertEquals(123L, again.lastOpenedAt)
        assertNull(again.archivedAt)
        assertTrue(again.capturedAt >= first.capturedAt)
    }

    @Test fun invalidSharedTextReturnsWithoutWriting() {
        send("This message contains no link").use { receiver ->
            compose.waitUntil(10_000) { receiver.state == Lifecycle.State.DESTROYED }
            assertTrue(rows().isEmpty())
        }
    }

    @Test fun failedPreviewKeepsUrlAndRetryReturnsToPending() {
        val original = "https://example.com/preview-failure"
        val saved = runBlocking { requireNotNull(app.repository.capture(original)) }
        compose.waitUntil(10_000) {
            WorkManager.getInstance(app).getWorkInfosForUniqueWork("preview-${saved.id}").get().any {
                it.state == WorkInfo.State.ENQUEUED && it.constraints.requiredNetworkType == NetworkType.CONNECTED
            }
        }
        runBlocking { dao.finishPreview(saved.id, null, null, null, null, null, "failed") }
        awaitRows { it.single().previewStatus == "failed" }
        compose.onNodeWithText(original).assertIsDisplayed()
        compose.onNodeWithText("Preview unavailable · link saved").assertIsDisplayed()
        compose.onNodeWithText("Retry preview").performClick()
        awaitRows { it.single().previewStatus == "pending" }
        compose.onNodeWithText("Saved offline · preview pending").assertIsDisplayed()
        compose.onNodeWithText(original).assertIsDisplayed()
        assertEquals(saved.id, rows().single().id)
        assertEquals(saved.revision, rows().single().revision)
    }

    @Test fun swipeRightArchivesAndSwipeLeftDeletesWithUndo() {
        val original = "https://example.com/swipe"
        runBlocking { app.repository.capture(original) }
        awaitRows { it.size == 1 }
        fun card() = compose.onNode(hasClickAction() and hasAnyDescendant(hasText(original)), useUnmergedTree = true)
        card().performTouchInput { swipeRight() }
        awaitRows { it.single().archivedAt != null }
        compose.onNodeWithText("Undo").performClick()
        awaitRows { it.single().archivedAt == null }
        card().performTouchInput { swipeLeft() }
        awaitRows { it.single().deletedAt != null }
        compose.onNodeWithText("Undo").performClick()
        awaitRows { it.single().deletedAt == null }
        compose.onNodeWithText(original).assertIsDisplayed()
    }

    @Test fun emptyViewsAndBackDismissDialogThenSearchThenReturnToMain() {
        val empty = "A quiet inbox. Share a link to my-feed from another app, or paste one here."
        compose.onNodeWithText(empty).assertIsDisplayed()
        compose.onNodeWithText("Grouped").performClick()
        compose.onNodeWithText(empty).assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithText("Main").assertIsSelected()
        compose.onNodeWithText("Archived").performClick()
        compose.onNodeWithText("No archived links yet.").assertIsDisplayed()
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithText("No matching links in Archived.").assertIsDisplayed()
        compose.onNodeWithText("Paste link").performClick()
        compose.onNodeWithText("URL or shared text").performTextInput("https://example.com/not-saved")
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        compose.onNodeWithText("Paste a link").assertDoesNotExist()
        compose.onNodeWithText("Close search").assertIsDisplayed()
        assertTrue(rows().isEmpty())
        Espresso.pressBack()
        compose.onNodeWithText("No archived links yet.").assertIsDisplayed()
        compose.onNodeWithText("Archived").assertIsSelected()
        Espresso.pressBack()
        compose.onNodeWithText("Main").assertIsSelected()
        compose.onNodeWithText(empty).assertIsDisplayed()
    }

    @Test fun partialSwipesShowActionAndCancelWithoutChangingRow() {
        val saved = runBlocking { requireNotNull(app.repository.capture("https://example.com/feedback")) }
        awaitRows { it.size == 1 }
        val card = compose.onNodeWithTag("link-${saved.id}")
        for ((direction, label) in listOf(1 to "Archive", -1 to "Delete")) {
            card.performTouchInput {
                down(center)
                moveTo(center + Offset(direction * width * 0.25f, 0f), 500)
            }
            // Visible background feedback is separate from the always available action button.
            compose.onNode(hasText(label) and !hasClickAction() and !hasAnyAncestor(hasClickAction()),
                useUnmergedTree = true).assertIsDisplayed()
            assertEquals(saved, rows().single())
            card.performTouchInput { cancel() }
            compose.waitForIdle()
            assertEquals(saved, rows().single())
            card.assertIsDisplayed()
        }
    }

    @Test fun labeledTouchControlsAndFailedCardWorkWithoutSwiping() {
        val saved = runBlocking { requireNotNull(app.repository.capture("https://example.com/accessible")) }
        runBlocking { dao.finishPreview(saved.id, null, null, null, null, null, "failed") }
        awaitRows { it.single().previewStatus == "failed" }
        listOf("Search", "Paste link", "Retry preview", "Archive", "Delete").forEach { label ->
            val bounds = compose.onNode(hasText(label) and hasClickAction())
                .assertIsDisplayed().fetchSemanticsNode().touchBoundsInRoot
            val minimum = with(compose.density) { 48.dp.toPx() } - 1f
            assertTrue("$label touch height: ${bounds.height}", bounds.height >= minimum)
            assertTrue("$label touch width: ${bounds.width}", bounds.width >= minimum)
        }
        listOf("Main", "Grouped", "Archived").forEach { label ->
            compose.onNodeWithText(label).assertHasClickAction().assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("link-${saved.id}").assertHasClickAction()
        compose.onNode(hasText("Archive") and hasClickAction()).performClick()
        awaitRows { it.single().archivedAt != null }
        compose.onNodeWithText("Undo").assertHasClickAction().performClick()
        awaitRows { it.single().archivedAt == null }
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()
        awaitRows { it.single().deletedAt != null }
        compose.onNodeWithText("Undo").performClick()
        awaitRows { it.single().deletedAt == null }
        compose.onNodeWithTag("link-${saved.id}").assertIsDisplayed()
    }

    @Test fun groupedAndArchivedRenderSimilarityFallbackAndCaptureDateSections() {
        val day = java.util.Calendar.getInstance().apply {
            set(2026, 0, 1, 0, 0, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        fun row(id: Long, title: String?, at: Long = day + id) = SavedLink(id = id,
            originalUrl = "https://example.com/$id", canonicalUrl = "https://example.com/$id",
            host = "example.com", capturedAt = at, title = title, previewStatus = "ready")
        runBlocking {
            dao.insert(row(1, "CAFÉ Android"))
            dao.insert(row(2, "cafe android guide"))
            dao.insert(row(3, null))
            dao.insert(row(4, "Café Android", day - 1).copy(archivedAt = day + 999))
            dao.insert(row(5, "Café Android").copy(archivedAt = day + 100))
            dao.insert(row(6, "deleted").copy(deletedAt = day + 100))
        }
        awaitRows { it.size == 6 }
        compose.onNodeWithText("Grouped").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("group-3").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("group-3").assertTextEquals("example.com · Untitled links · 1")
        compose.onNodeWithTag("feed-list").performScrollToNode(hasTestTag("group-2"))
        compose.onNodeWithTag("group-2").assertTextEquals("example.com · cafe android guide · 2")
        listOf(1L, 2L, 3L).forEach { id ->
            compose.onNodeWithTag("feed-list").performScrollToNode(hasTestTag("link-$id"))
            compose.onAllNodesWithTag("link-$id").assertCountEquals(1)
        }
        compose.onNodeWithText("Archived").performClick()
        compose.onNodeWithTag("feed-list").performScrollToNode(hasText("Captured 2026-01-01"))
        compose.onNodeWithTag("feed-list").performScrollToNode(hasTestTag("group-5"))
        compose.onNodeWithTag("group-5").assertTextEquals("example.com · Café Android · 1")
        compose.onNodeWithTag("feed-list").performScrollToNode(hasText("Captured 2025-12-31"))
        compose.onNodeWithTag("feed-list").performScrollToNode(hasTestTag("link-4"))
        compose.onNodeWithTag("link-4").assertIsDisplayed()
        compose.onNodeWithTag("link-6").assertDoesNotExist()
    }

    @Test fun scopedSearchReactsToLifecycleMetadataAndRecreationAndBackReturnsToFeed() {
        runBlocking {
            dao.insert(SavedLink(id = 1, originalUrl = "https://example.com/needle", canonicalUrl = "https://example.com/needle",
                host = "example.com", capturedAt = 100, previewStatus = "pending"))
            dao.insert(SavedLink(id = 2, originalUrl = "https://example.com/archive", canonicalUrl = "https://example.com/archive",
                host = "example.com", capturedAt = 200, description = "needle in description", archivedAt = 300, previewStatus = "ready"))
            dao.insert(SavedLink(id = 3, originalUrl = "https://example.com/deleted", canonicalUrl = "https://example.com/deleted",
                host = "example.com", capturedAt = 400, title = "needle", deletedAt = 500))
        }
        awaitRows { it.size == 3 }
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithTag("scope-Inbox").assertIsSelected()
        compose.onNodeWithText("Search saved links").performTextInput("NEEDLE")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("1 results · Inbox · newest first").assertIsDisplayed()
        compose.onNodeWithTag("link-1").assertIsDisplayed()
        compose.onNode(hasText("Archive") and hasClickAction()).performClick()
        awaitRows { it.first { row -> row.id == 1L }.archivedAt != null }
        compose.onNodeWithText("No matching links in Inbox.").assertIsDisplayed()
        compose.onNodeWithTag("scope-Archived").performClick()
        compose.onNodeWithText("2 results · Archived · newest first").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Search saved links").assertTextContains("NEEDLE")
        compose.onNodeWithTag("scope-Archived").assertIsSelected()
        compose.onNodeWithTag("scope-All").performClick()
        compose.onNodeWithTag("feed-list").performScrollToNode(hasTestTag("link-1"))
        compose.onNodeWithTag("link-1").assertIsDisplayed()
        runBlocking { dao.act(1, LinkAction.Delete, 600) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("link-1").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("1 results · All · newest first").assertIsDisplayed()
        compose.onNodeWithText("Search saved links").performTextReplacement("Café 東京")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithText("No matching links in All.").assertIsDisplayed()
        runBlocking {
            dao.capture(requireNotNull(org.wastingnotime.myfeed.data.UrlCapture.normalize("https://example.com/needle")), 700)
            dao.finishPreview(1, "CAFÉ 東京", null, null, null, null, "ready")
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("link-1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("link-1").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithText("Search").assertIsDisplayed()
        compose.onNodeWithText("Grouped").performClick()
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithTag("scope-Inbox").assertIsSelected()
        compose.onNodeWithText("Close search").performClick()
        compose.onNodeWithText("Archived").performClick()
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithTag("scope-Archived").assertIsSelected()
        compose.onNodeWithText("Close search").performClick()
        Espresso.pressBack()
        compose.onNodeWithText("Your inbox · newest first").assertIsDisplayed()
    }
}
