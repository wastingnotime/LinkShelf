package org.wastingnotime.linkshelf.data

class LinkRepository(private val dao: LinkDao, private val enqueuePreview: (Long) -> Unit = {}, private val now: () -> Long = System::currentTimeMillis) {
    val links = dao.observeVisible()
    suspend fun capture(text: String): SavedLink? = UrlCapture.extract(text)?.let {
        dao.capture(it, now()).also { saved ->
            if (saved.previewStatus == "pending") schedule(saved.id)
        }
    }
    private fun schedule(id: Long) {
        // Room is the durable outbox. Scheduling failure must never fail a committed capture.
        try { enqueuePreview(id) } catch (_: Exception) { /* recovered on next app start */ }
    }
    suspend fun retryPreview(id: Long) {
        if (dao.retryPreview(id) == 1) schedule(id)
    }
    suspend fun act(id: Long, action: LinkAction): UndoToken? = dao.act(id, action, now())
    suspend fun undo(token: UndoToken): Boolean = dao.undo(token).also { restored ->
        if (restored) dao.find(token.id)?.takeIf { it.deletedAt == null && it.previewStatus == "pending" }
            ?.let { schedule(it.id) }
    }
    suspend fun toggleFavorite(id: Long) = dao.toggleFavorite(id)
    suspend fun markOpened(id: Long) = dao.markOpened(id, now())
}
