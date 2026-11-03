package org.wastingnotime.linkshelf.data

import androidx.room.withTransaction
import org.json.JSONArray
import org.json.JSONObject

/** Portable link data only; cache files and device-local IDs never cross devices. */
object FeedBackup {
    suspend fun export(db: FeedDatabase): String {
        val rows = JSONArray()
        db.links().all().forEach { link ->
            rows.put(JSONObject().apply {
                put("url", link.originalUrl); put("capturedAt", link.capturedAt)
                put("archivedAt", link.archivedAt); put("deletedAt", link.deletedAt)
                put("lastOpenedAt", link.lastOpenedAt); put("title", link.title)
                put("description", link.description); put("siteName", link.siteName)
                put("isFavorite", link.isFavorite)
            })
        }
        val raw = JSONObject().put("format", "linkshelf").put("formatVersion", 1).put("links", rows).toString(2)
        parse(raw) // Never publish a successful backup that this version cannot restore.
        return raw
    }

    fun parse(raw: String): List<SavedLink> {
        require(raw.toByteArray().size <= MAX_BYTES) { "Backup exceeds 10 MB" }
        val root = JSONObject(raw)
        require(root.opt("format") in setOf("linkshelf", "my-feed") && root.opt("formatVersion") == 1) { "Unsupported backup format" }
        val rows = root.getJSONArray("links")
        require(rows.length() <= 10000) { "Too many links" }
        val canonicalUrls = mutableSetOf<String>()
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val rawUrl = row.opt("url") as? String ?: error("Invalid URL type in backup")
            val url = requireNotNull(UrlCapture.normalize(rawUrl)) { "Invalid link in backup" }
            require(canonicalUrls.add(url.canonical)) { "Duplicate link in backup" }
            fun timestamp(key: String): Long? {
                if (row.isNull(key)) return null
                val value = row.get(key)
                require(value is Long || value is Int) { "Invalid $key timestamp" }
                return (value as Number).toLong().also { require(it >= 0) { "Negative $key timestamp" } }
            }
            fun text(key: String, limit: Int): String? = if (row.isNull(key)) null else (row.get(key) as? String ?: throw IllegalArgumentException("Invalid $key text")).also { require(it.length <= limit) }
            val favorite = if (!row.has("isFavorite")) false else
                row.get("isFavorite") as? Boolean ?: throw IllegalArgumentException("Invalid favorite state")
            SavedLink(originalUrl = url.original, canonicalUrl = url.canonical, host = url.host,
                capturedAt = requireNotNull(timestamp("capturedAt")), archivedAt = timestamp("archivedAt"),
                deletedAt = timestamp("deletedAt"), lastOpenedAt = timestamp("lastOpenedAt"),
                title = text("title", 300), description = text("description", 1000), siteName = text("siteName", 150), isFavorite = favorite)
        }
    }

    suspend fun import(db: FeedDatabase, raw: String): Int {
        val links = parse(raw) // Validate the entire file before changing anything.
        return db.withTransaction { links.count { db.links().insert(it) != -1L } }
    }
    const val MAX_BYTES = 10 * 1024 * 1024
}
