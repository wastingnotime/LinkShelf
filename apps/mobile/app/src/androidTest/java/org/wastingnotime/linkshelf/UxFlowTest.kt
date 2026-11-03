package org.wastingnotime.linkshelf

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.wastingnotime.linkshelf.data.*

@RunWith(AndroidJUnit4::class)
class UxFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as FeedApplication
    @Before fun setup() = runBlocking(Dispatchers.IO) {
        WorkManager.getInstance(app).cancelAllWork().result.get()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
        app.database.clearAllTables()
    }
    @Test fun invalidPasteSurvivesCorrectionAndRecreation() {
        compose.onNodeWithContentDescription("Paste link").performClick()
        compose.onNodeWithText("URL or shared text").performTextInput("invalid text")
        compose.onNodeWithText("Save link").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Enter a valid HTTP or HTTPS link").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("invalid text").assertExists()
        compose.onNodeWithText("URL or shared text").performTextReplacement("https://example.com/corrected")
        compose.onNodeWithText("Save link").performClick()
        compose.waitUntil(5000) { runBlocking { app.database.links().all().size == 1 } }
        compose.onNodeWithText("URL or shared text").assertDoesNotExist()
    }
    @Test fun deletedArchiveRestoresToArchiveAndCannotBeUndoneByStaleToken() {
        val pair = runBlocking {
            val dao = app.database.links()
            val saved = dao.capture(requireNotNull(UrlCapture.normalize("https://example.com/deleted")), 100)
            dao.act(saved.id, LinkAction.Archive, 200)
            saved.id to requireNotNull(dao.act(saved.id, LinkAction.Delete, 300))
        }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Recently deleted").performClick()
        compose.onNodeWithText("Restore to Archived").performClick()
        compose.waitUntil(5000) { runBlocking { app.database.links().find(pair.first)?.deletedAt == null } }
        runBlocking {
            assertEquals(200L, app.database.links().find(pair.first)?.archivedAt)
            assertFalse(app.database.links().undo(pair.second))
        }
        compose.onNodeWithText("No deleted links").assertExists()
    }
    @Test fun backupScreenAndAppearanceHaveClearState() {
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Backup").performClick()
        compose.onNodeWithText("Manual backup").assertIsDisplayed()
        compose.onNodeWithText("Import backup").assertIsDisplayed()
        compose.onNodeWithText("Automatic backup").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Dark").performClick().assertIsSelected()
        compose.onNodeWithText("System").performClick().assertIsSelected()
        compose.onNodeWithText("Close").performClick()
    }
}
