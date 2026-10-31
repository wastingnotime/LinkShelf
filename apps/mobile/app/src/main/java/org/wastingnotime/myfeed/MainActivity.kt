package org.wastingnotime.myfeed

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.net.toUri
import org.wastingnotime.myfeed.ui.FeedScreen
import org.wastingnotime.myfeed.ui.FeedViewModel

class MainActivity : ComponentActivity() {
    private val model: FeedViewModel by viewModels { FeedViewModel.Factory(application as FeedApplication) }

    override fun onCreate(savedInstanceState: Bundle?) {
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
                    model.notify("No app available to open this link")
                } catch (_: SecurityException) {
                    model.notify("Android could not open this link")
                }
            }
        }
    }
}
