package org.wastingnotime.linkshelf

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.wastingnotime.linkshelf.data.*

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class FavoritesLocaleFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<FeedApplication>()
    private val locales get() = app.getSystemService(LocaleManager::class.java)

    @Before fun setup() = runBlocking {
        WorkManager.getInstance(app).cancelAllWork().result.get()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().build())
        app.database.clearAllTables()
    }
    @After fun resetLocale() { locales.applicationLocales = LocaleList.getEmptyLocaleList() }

    @Test fun englishFavoritesCanBeToggledAndPersistAcrossRecreation() = verify("en-US", "Grouped", "Favorites · 1", "Add to favorites", "Remove from favorites")
    @Test fun portugueseFavoritesAndSettingsUseTranslatedLabels() = verify("pt-BR", "Agrupados", "Favoritos · 1", "Adicionar aos favoritos", "Remover dos favoritos")

    private fun verify(tag: String, grouped: String, heading: String, add: String, remove: String) {
        locales.applicationLocales = LocaleList.forLanguageTags(tag)
        runBlocking {
            app.database.links().insert(SavedLink(id = 1, originalUrl = "https://example.com/older", canonicalUrl = "https://example.com/older",
                host = "example.com", capturedAt = 100, title = "Older favorite", previewStatus = "ready"))
            app.database.links().insert(SavedLink(id = 2, originalUrl = "https://example.com/newer", canonicalUrl = "https://example.com/newer",
                host = "example.com", capturedAt = 200, title = "Newer regular", previewStatus = "ready"))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("link-1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("feed-list").performScrollToNode(hasTestTag("link-1"))
            compose.onNodeWithTag("favorite-1").assertIsOff().assertContentDescriptionEquals(add).performClick()
            compose.waitUntil(5_000) { runBlocking { app.database.links().find(1)!!.isFavorite } }
            compose.onNodeWithText(grouped).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("favorites-heading").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("favorites-heading").assertTextEquals(heading).assertIsDisplayed()
            compose.onNodeWithTag("favorite-1").assertIsOn().assertContentDescriptionEquals(remove)
            compose.onNodeWithTag("feed-list").performScrollToNode(hasTestTag("link-2"))
            compose.onAllNodesWithTag("link-2").assertCountEquals(1)
            scenario.recreate()
            compose.onNodeWithTag("feed-list").performScrollToIndex(0)
            compose.onNodeWithTag("favorites-heading").assertIsDisplayed()
            compose.onNodeWithTag("favorite-1").assertIsOn().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("favorites-heading").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("feed-list").performScrollToNode(hasTestTag("link-1"))
            compose.onNodeWithTag("favorite-1").assertIsOff()
            if (tag == "pt-BR") {
                compose.onNodeWithContentDescription("Mais").performClick()
                compose.onNodeWithText("Aparência").performClick()
                compose.onNodeWithText("Sistema").assertExists()
                compose.onNodeWithText("Claro").assertExists()
                compose.onNodeWithText("Escuro").assertExists()
                compose.onNodeWithText("Fechar").performClick()
                compose.onNodeWithContentDescription("Colar link").performClick()
                compose.onNodeWithText("URL ou texto compartilhado").assertExists()
                compose.onNodeWithText("Cancelar").performClick()
            }
        }
    }
}
