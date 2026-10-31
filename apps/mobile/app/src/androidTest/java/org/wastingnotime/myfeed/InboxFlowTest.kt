package org.wastingnotime.myfeed

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.room.withTransaction
import androidx.work.WorkManager
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
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
        compose.onNodeWithText("Grouping is coming. For now, this shows the same inbox as Main.").assertIsDisplayed()
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
}
