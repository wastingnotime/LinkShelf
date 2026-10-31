package org.wastingnotime.myfeed

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/** A separate, standard-launch activity handles each SEND; no form or confirmation. */
class ShareActivity : ComponentActivity() {
    private val capture: ShareCapture by viewModels {
        val app = application as FeedApplication
        val text = if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true)
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty() else ""
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ShareCapture(app, text) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val result = capture.result.await()
            Toast.makeText(this@ShareActivity, result, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

class ShareCapture(app: FeedApplication, text: String) : ViewModel() {
    // Rotation/back cannot cancel a database save. Activity finishes only after commit.
    val result = app.applicationScope.async {
        try {
            if (app.repository.capture(text) == null) "No valid HTTP or HTTPS link found" else "Saved to my-feed"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            "Could not save link. Please share again."
        }
    }
}
