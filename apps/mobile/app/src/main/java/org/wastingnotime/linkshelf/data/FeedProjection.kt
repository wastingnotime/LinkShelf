package org.wastingnotime.linkshelf.data

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class SearchScope { Inbox, Archived, All }
enum class FeedDestination { Main, Grouped, Archived }
data class FeedSelection(
    val destination: FeedDestination = FeedDestination.Main,
    val searching: Boolean = false,
    val query: String = "",
    val scope: SearchScope = SearchScope.Inbox,
)
data class LinkGroup(val links: List<SavedLink>, val hasTitleKeywords: Boolean, val isFavorites: Boolean = false) {
    val representative: SavedLink get() = links.first()
}
data class ArchiveDay(val day: String, val groups: List<LinkGroup>)
data class FeedContent(
    val links: List<SavedLink> = emptyList(),
    val groups: List<LinkGroup> = emptyList(),
    val days: List<ArchiveDay> = emptyList(),
)

/** Pure derived views. Policy and intentional language/scale limits: docs/search-and-grouping.md. */
object FeedProjection {
    val recency: Comparator<SavedLink> = compareByDescending<SavedLink> { it.capturedAt }.thenByDescending { it.id }
    private val marks = Regex("\\p{M}+")
    private val words = Regex("[\\p{L}\\p{N}]+")
    private val whitespace = Regex("[\\p{Z}\\s\\u0085]+")

    private fun normalize(text: String): String = marks.replace(Normalizer.normalize(
        Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT), Normalizer.Form.NFD), "")

    fun keywords(title: String?): Set<String> = words.findAll(normalize(title.orEmpty())).map { it.value }.toSet()
    private fun searchable(text: String): String = whitespace.replace(normalize(text), " ").trim()

    fun select(links: List<SavedLink>, scope: SearchScope, query: String = ""): List<SavedLink> {
        val needle = searchable(query)
        return links.filter { link ->
            link.deletedAt == null && when (scope) {
                SearchScope.Inbox -> link.archivedAt == null
                SearchScope.Archived -> link.archivedAt != null
                SearchScope.All -> true
            } && (needle.isEmpty() || sequenceOf(link.title, link.description, link.originalUrl, link.canonicalUrl)
                .filterNotNull().any { searchable(it).contains(needle) })
        }.sortedWith(recency)
    }

    fun group(links: List<SavedLink>): List<LinkGroup> {
        // Insertion order is representative recency, also the tie-breaking order.
        data class Bucket(val tokens: Set<String>, val members: MutableList<SavedLink>)
        val byHost = mutableMapOf<String, MutableList<Bucket>>()
        val ordered = mutableListOf<Bucket>()
        for (link in links.filter { it.deletedAt == null }.sortedWith(recency)) {
            val tokens = keywords(link.title)
            val candidates = byHost.getOrPut(link.host) { mutableListOf() }
            var best: Bucket? = null
            var bestScore = -1.0
            for (candidate in candidates) {
                val score = if (tokens.isEmpty() && candidate.tokens.isEmpty()) 1.0
                    else if (tokens.isEmpty() || candidate.tokens.isEmpty()) 0.0
                    else {
                        val shared = tokens.count { it in candidate.tokens }
                        shared.toDouble() / (tokens.size + candidate.tokens.size - shared)
                    }
                if (score >= 0.5 && score > bestScore) { best = candidate; bestScore = score }
            }
            if (best != null) best.members.add(link)
            else Bucket(tokens, mutableListOf(link)).also { candidates.add(it); ordered.add(it) }
        }
        return ordered.map { LinkGroup(it.members.toList(), it.tokens.isNotEmpty()) }
    }

    fun groupedInbox(links: List<SavedLink>): List<LinkGroup> {
        val inbox = select(links, SearchScope.Inbox)
        val favorites = inbox.filter { it.isFavorite }
        return (if (favorites.isEmpty()) emptyList() else listOf(LinkGroup(favorites, false, isFavorites = true))) +
            group(inbox.filterNot { it.isFavorite })
    }

    fun archive(links: List<SavedLink>, zone: TimeZone = TimeZone.getDefault()): List<ArchiveDay> {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone }
        return select(links, SearchScope.Archived).groupBy { format.format(Date(it.capturedAt)) }
            .map { (day, rows) -> ArchiveDay(day, group(rows)) }
    }

    fun project(links: List<SavedLink>, selection: FeedSelection): FeedContent {
        if (selection.searching) return FeedContent(links = select(links, selection.scope, selection.query))
        val scope = if (selection.destination == FeedDestination.Archived) SearchScope.Archived else SearchScope.Inbox
        val selected = select(links, scope)
        return FeedContent(links = selected,
            groups = if (selection.destination == FeedDestination.Grouped) groupedInbox(selected) else emptyList(),
            days = if (selection.destination == FeedDestination.Archived) archive(selected) else emptyList())
    }
}
