package org.wastingnotime.myfeed.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LinkDao {
    @Query("SELECT * FROM saved_links WHERE deletedAt IS NULL ORDER BY capturedAt DESC, id DESC")
    abstract fun observeVisible(): Flow<List<SavedLink>>

    @Query("SELECT * FROM saved_links WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC, id DESC")
    abstract fun observeDeleted(): Flow<List<SavedLink>>

    @Query("UPDATE saved_links SET deletedAt = NULL, revision = revision + 1 WHERE id = :id AND deletedAt IS NOT NULL")
    abstract suspend fun restoreDeleted(id: Long): Int

    @Query("SELECT * FROM saved_links ORDER BY capturedAt DESC, id DESC")
    abstract suspend fun all(): List<SavedLink>

    @Query("SELECT * FROM saved_links WHERE id = :id")
    abstract suspend fun find(id: Long): SavedLink?

    @Query("SELECT * FROM saved_links WHERE canonicalUrl = :canonical")
    abstract suspend fun findCanonical(canonical: String): SavedLink?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(link: SavedLink): Long

    @Query("""UPDATE saved_links SET capturedAt = MAX(capturedAt, :now),
        archivedAt = NULL, deletedAt = NULL,
        previewStatus = CASE WHEN (previewStatus LIKE 'failed%' OR previewStatus LIKE 'partial%' OR (previewStatus = 'ready' AND imageUrl IS NOT NULL AND imagePath IS NULL)) THEN 'pending' ELSE previewStatus END, revision = revision + 1 WHERE canonicalUrl = :canonical""")
    abstract suspend fun refresh(canonical: String, now: Long)

    @Transaction
    open suspend fun capture(url: SharedUrl, now: Long): SavedLink {
        val id = insert(SavedLink(originalUrl = url.original, canonicalUrl = url.canonical,
            host = url.host, capturedAt = now))
        if (id == -1L) refresh(url.canonical, now)
        return requireNotNull(findCanonical(url.canonical))
    }

    @Query("""UPDATE saved_links SET archivedAt = :archivedAt, deletedAt = :deletedAt,
        revision = revision + 1 WHERE id = :id AND revision = :expectedRevision""")
    abstract suspend fun changeState(id: Long, archivedAt: Long?, deletedAt: Long?, expectedRevision: Long): Int

    @Transaction
    open suspend fun act(id: Long, action: LinkAction, now: Long): UndoToken? {
        val before = find(id) ?: return null
        if (before.deletedAt != null) return null
        val archived = when (action) {
            LinkAction.Archive -> { if (before.archivedAt != null) return null; now }
            LinkAction.Restore -> { if (before.archivedAt == null) return null; null }
            LinkAction.Delete -> before.archivedAt
        }
        changeState(id, archived, if (action == LinkAction.Delete) now else null, before.revision)
        return UndoToken(id, before.archivedAt, before.deletedAt, before.revision + 1)
    }

    // Compare-and-set prevents an old snackbar from reversing a newer capture/action.
    suspend fun undo(token: UndoToken): Boolean = changeState(
        token.id, token.archivedAt, token.deletedAt, token.expectedRevision) == 1

    @Query("SELECT id FROM saved_links WHERE previewStatus = 'pending' AND deletedAt IS NULL")
    abstract suspend fun pendingPreviews(): List<Long>

    @Query("UPDATE saved_links SET previewStatus = 'pending' WHERE id = :id AND deletedAt IS NULL AND previewStatus != 'pending'")
    abstract suspend fun retryPreview(id: Long): Int

    // Enrichment never touches lifecycle revisions, ordering, identity, or opening history.
    @Query("""UPDATE saved_links SET title = :title, description = :description,
        siteName = :siteName, imageUrl = :imageUrl, imagePath = :imagePath, previewStatus = :status
        WHERE id = :id AND previewStatus = 'pending' AND deletedAt IS NULL""")
    abstract suspend fun finishPreview(id: Long, title: String?, description: String?, siteName: String?,
        imageUrl: String?, imagePath: String?, status: String): Int

    @Query("UPDATE saved_links SET lastOpenedAt = :now WHERE id = :id")
    abstract suspend fun markOpened(id: Long, now: Long)
}
