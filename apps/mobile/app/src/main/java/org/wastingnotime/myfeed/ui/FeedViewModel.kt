package org.wastingnotime.myfeed.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.wastingnotime.myfeed.FeedApplication
import org.wastingnotime.myfeed.data.LinkAction
import org.wastingnotime.myfeed.data.SavedLink
import org.wastingnotime.myfeed.data.UndoToken

data class FeedState(val loading: Boolean = true, val links: List<SavedLink> = emptyList())
data class Notice(val text: String, val undo: UndoToken? = null)

class FeedViewModel(private val app: FeedApplication) : ViewModel() {
    private val repository = app.repository
    val state = repository.links.map { FeedState(false, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedState())
    private val notices = Channel<Notice>(Channel.BUFFERED)
    val events = notices.receiveAsFlow()

    fun notify(text: String) { notices.trySend(Notice(text)) }

    // Saves and lifecycle writes outlive a UI recreation or system Back.
    private fun write(block: suspend () -> Unit) {
        app.applicationScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notify("Could not update inbox. Please try again.") }
        }
    }

    fun capture(text: String) = write {
        notify(if (repository.capture(text) == null) "No valid HTTP or HTTPS link found" else "Saved to inbox")
    }

    fun act(id: Long, action: LinkAction) = write {
        repository.act(id, action)?.let { token ->
            val label = when (action) {
                LinkAction.Archive -> "Link archived"
                LinkAction.Delete -> "Link deleted"
                LinkAction.Restore -> "Link restored to inbox"
            }
            notices.trySend(Notice(label, token))
        }
    }

    fun undo(token: UndoToken) = write {
        if (!repository.undo(token)) notify("Link changed since that action; kept its newer state")
    }

    fun retryPreview(id: Long) = write { repository.retryPreview(id) }

    fun opened(id: Long) = write { repository.markOpened(id) }

    class Factory(private val app: FeedApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FeedViewModel(app) as T
    }
}
