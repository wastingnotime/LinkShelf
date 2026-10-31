package org.wastingnotime.myfeed.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "saved_links", indices = [Index(value = ["canonicalUrl"], unique = true)])
data class SavedLink(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalUrl: String,
    val canonicalUrl: String,
    val host: String,
    val capturedAt: Long,
    val archivedAt: Long? = null,
    val deletedAt: Long? = null,
    val lastOpenedAt: Long? = null,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val siteName: String? = null,
    val previewStatus: String = "fallback",
    val revision: Long = 0,
)

data class UndoToken(
    val id: Long,
    val archivedAt: Long?,
    val deletedAt: Long?,
    val expectedRevision: Long,
)

enum class LinkAction { Archive, Delete, Restore }
