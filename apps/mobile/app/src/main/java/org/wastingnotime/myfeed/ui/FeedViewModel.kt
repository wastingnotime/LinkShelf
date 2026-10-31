package org.wastingnotime.myfeed.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.wastingnotime.myfeed.FeedApplication
import org.wastingnotime.myfeed.data.LinkAction
import org.wastingnotime.myfeed.data.UndoToken
import org.wastingnotime.myfeed.data.FeedContent
import org.wastingnotime.myfeed.data.FeedDestination
import org.wastingnotime.myfeed.data.FeedProjection
import org.wastingnotime.myfeed.data.FeedSelection
import org.wastingnotime.myfeed.data.SearchScope

data class FeedState(val loading: Boolean = true, val selection: FeedSelection = FeedSelection(),
    val content: FeedContent = FeedContent())
data class Notice(val text: String, val undo: UndoToken? = null)

class FeedViewModel(private val app: FeedApplication) : ViewModel() {
    private val repository = app.repository
    private val selected = MutableStateFlow(FeedSelection())
    private val zoneVersion = MutableStateFlow(0)
    val selection = selected.asStateFlow()
    val state = combine(repository.links, selected, zoneVersion) { links, selection, _ ->
        FeedState(false, selection, FeedProjection.project(links, selection))
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedState())
    private val notices = Channel<Notice>(Channel.BUFFERED)
    val events = notices.receiveAsFlow()

    fun notify(text: String) { notices.trySend(Notice(text)) }

    fun navigate(destination: FeedDestination) { selected.update { it.copy(destination = destination) } }
    fun startSearch() { selected.update { it.copy(searching = true, query = "",
        scope = if (it.destination == FeedDestination.Archived) SearchScope.Archived else SearchScope.Inbox) } }
    fun closeSearch() { selected.update { it.copy(searching = false) } }
    fun search(query: String) { selected.update { it.copy(query = query) } }
    fun scope(scope: SearchScope) { selected.update { it.copy(scope = scope) } }

    // Re-evaluate calendar-day sections if the device time zone changed while away.
    fun refresh() { zoneVersion.update { it + 1 } }

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
