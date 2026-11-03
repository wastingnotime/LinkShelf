package org.wastingnotime.linkshelf.ui

import org.wastingnotime.linkshelf.R
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
import org.wastingnotime.linkshelf.FeedApplication
import org.wastingnotime.linkshelf.data.LinkAction
import org.wastingnotime.linkshelf.data.UndoToken
import org.wastingnotime.linkshelf.data.FeedContent
import org.wastingnotime.linkshelf.data.FeedDestination
import org.wastingnotime.linkshelf.data.FeedProjection
import org.wastingnotime.linkshelf.data.FeedSelection
import org.wastingnotime.linkshelf.data.SearchScope

data class FeedState(val loading: Boolean = true, val selection: FeedSelection = FeedSelection(),
    val content: FeedContent = FeedContent())
data class SettingsState(val busy: Boolean = false, val message: String? = null, val importRaw: String? = null)
data class PasteState(val text: String = "", val saving: Boolean = false, val error: String? = null, val saved: Boolean = false)
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
    fun refresh() {
        zoneVersion.update { it + 1 }
        write { org.wastingnotime.linkshelf.data.DeletedLinkCleanup(app, app.database.links()).purge() }
    }

    // Saves and lifecycle writes outlive a UI recreation or system Back.
    private fun write(block: suspend () -> Unit) {
        app.applicationScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notify(app.getString(R.string.update_failed)) }
        }
    }

    private val settingState = MutableStateFlow(SettingsState())
    val settings = settingState.asStateFlow()
    fun pendingImport(raw: String?) { settingState.update { it.copy(importRaw = raw) } }
    fun settingsOperation(block: suspend () -> String) {
        if (settingState.value.busy) return
        settingState.update { it.copy(busy = true, message = null) }
        app.applicationScope.launch {
            val message = try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: org.json.JSONException) { app.getString(R.string.invalid_backup_file) }
            catch (e: IllegalArgumentException) { app.getString(R.string.invalid_backup) }
            catch (_: SecurityException) { app.getString(R.string.access_lost) }
            catch (_: java.io.IOException) { app.getString(R.string.file_failed) }
            catch (e: Exception) { app.getString(R.string.operation_failed) }
            settingState.update { it.copy(busy = false, message = message) }
        }
    }
    val deleted = app.database.links().observeDeleted().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val pasteState = MutableStateFlow(PasteState())
    val paste = pasteState.asStateFlow()
    fun editPaste(text: String) { if (!pasteState.value.saving) pasteState.value = PasteState(text) }
    fun resetPaste() { pasteState.value = PasteState() }
    fun savePaste() {
        val before = pasteState.value
        if (before.saving) return
        pasteState.value = before.copy(saving = true, error = null)
        app.applicationScope.launch {
            try {
                val saved = repository.capture(before.text)
                pasteState.value = before.copy(saved = saved != null,
                    error = if (saved == null) app.getString(R.string.invalid_link_input) else null)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { pasteState.value = before.copy(error = app.getString(R.string.save_failed)) }
        }
    }
    fun permanentlyDelete(id: Long) = write {
        if (org.wastingnotime.linkshelf.data.DeletedLinkCleanup(app, app.database.links()).permanentlyDelete(id))
            notify(app.getString(R.string.permanently_deleted))
    }

    fun restoreDeleted(id: Long) = write {
        if (app.database.links().restoreDeleted(id) == 1) {
            app.database.links().find(id)?.takeIf { it.previewStatus == "pending" }?.let {
                org.wastingnotime.linkshelf.preview.PreviewScheduler(app).enqueue(id, recover = true)
            }
            notify(app.getString(R.string.restored_previous))
        }
    }

    fun capture(text: String) = write {
        notify(if (repository.capture(text) == null) app.getString(R.string.no_valid_link) else app.getString(R.string.saved_inbox))
    }

    fun act(id: Long, action: LinkAction) = write {
        repository.act(id, action)?.let { token ->
            val label = when (action) {
                LinkAction.Archive -> app.getString(R.string.link_archived)
                LinkAction.Delete -> app.getString(R.string.link_deleted)
                LinkAction.Restore -> app.getString(R.string.link_restored)
            }
            notices.trySend(Notice(label, token))
        }
    }

    fun undo(token: UndoToken) = write {
        if (!repository.undo(token)) notify(app.getString(R.string.undo_stale))
    }

    fun retryPreview(id: Long) = write { repository.retryPreview(id) }

    fun toggleFavorite(id: Long) = write { repository.toggleFavorite(id) }

    fun opened(id: Long) = write { repository.markOpened(id) }

    class Factory(private val app: FeedApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FeedViewModel(app) as T
    }
}
