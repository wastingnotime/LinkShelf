package org.wastingnotime.linkshelf

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import org.wastingnotime.linkshelf.ui.FeedScreen
import org.wastingnotime.linkshelf.ui.FeedViewModel
import org.wastingnotime.linkshelf.ui.launchAppearanceContext

class MainActivity : ComponentActivity() {
    private val model: FeedViewModel by viewModels { FeedViewModel.Factory(application as FeedApplication) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(launchAppearanceContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FeedScreen(model) { link ->
                try {
                    // Android intent filters match scheme case-sensitively. Keep the
                    // original address/fragment/query; only normalize its scheme casing.
                    startActivity(Intent(Intent.ACTION_VIEW, link.originalUrl.toUri().normalizeScheme()))
                    model.opened(link.id)
                } catch (_: ActivityNotFoundException) {
                    model.notify(getString(R.string.no_link_app))
                } catch (_: SecurityException) {
                    model.notify(getString(R.string.open_failed))
                }
            }
        }
    }
}
