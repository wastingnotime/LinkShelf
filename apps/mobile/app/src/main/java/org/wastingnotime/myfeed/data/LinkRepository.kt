package org.wastingnotime.myfeed.data

class LinkRepository(private val dao: LinkDao, private val now: () -> Long = System::currentTimeMillis) {
    val links = dao.observeVisible()
    suspend fun capture(text: String): SavedLink? = UrlCapture.extract(text)?.let { dao.capture(it, now()) }
    suspend fun act(id: Long, action: LinkAction): UndoToken? = dao.act(id, action, now())
    suspend fun undo(token: UndoToken): Boolean = dao.undo(token)
    suspend fun markOpened(id: Long) = dao.markOpened(id, now())
}
