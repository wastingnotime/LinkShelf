package org.wastingnotime.linkshelf

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalizationTest {
    @Test fun englishPortugueseAndUnsupportedLocalesResolveUiAndFeedback() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        fun localized(tag: String) = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(tag))
        })
        val en = localized("en-US")
        val pt = localized("pt-BR")
        assertEquals("Inbox", en.getString(R.string.inbox))
        assertEquals("Entrada", pt.getString(R.string.inbox))
        assertEquals("Favorites · 2", en.getString(R.string.favorites_heading, 2))
        assertEquals("Favoritos · 2", pt.getString(R.string.favorites_heading, 2))
        assertEquals("Remover dos favoritos", pt.getString(R.string.remove_favorite))
        assertEquals("Salvo no LinkShelf", pt.getString(R.string.capture_saved))
        assertEquals("Aparência", pt.getString(R.string.appearance))
        assertEquals("Restaurar para Arquivados", pt.getString(R.string.restore_destination, pt.getString(R.string.archived)))
        assertEquals("Inbox", localized("ja-JP").getString(R.string.inbox))
    }
}
