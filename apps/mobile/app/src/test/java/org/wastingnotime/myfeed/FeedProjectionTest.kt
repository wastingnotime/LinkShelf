package org.wastingnotime.myfeed

import org.junit.Assert.*
import org.junit.Test
import org.wastingnotime.myfeed.data.*
import java.util.Locale
import java.util.TimeZone

class FeedProjectionTest {
    private fun link(id: Long, title: String? = null, host: String = "example.com", at: Long = id) =
        SavedLink(id = id, originalUrl = "https://$host/$id#Original", canonicalUrl = "https://$host/$id",
            host = host, capturedAt = at, title = title)
    private fun ids(groups: List<LinkGroup>) = groups.map { it.links.map(SavedLink::id) }

    @Test fun normalizationHandlesMultilingualCompatibilityAccentsRepetitionAndEmptyTitles() {
        assertEquals(setOf("cafe", "android", "42"), FeedProjection.keywords("ＣＡＦÉ café ANDROID! 42"))
        assertEquals(setOf("привет", "мир", "東京", "العربية"), FeedProjection.keywords("Привет мир 東京 العربية"))
        assertEquals(FeedProjection.keywords("ação"), FeedProjection.keywords("ac\u0327a\u0303o"))
        listOf(null, "", " \t\n", "🎉 — !!!").forEach { assertTrue(FeedProjection.keywords(it).isEmpty()) }
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(setOf("inbox", "istanbul"), FeedProjection.keywords("INBOX İSTANBUL"))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun exactThresholdHostAndFixedRepresentativePreventChaining() {
        // A~B=2/3, B~C=2/3, A~C=1/3: C must start a group.
        val chain = listOf(link(3, "a b"), link(2, "a b c"), link(1, "b c"))
        assertEquals(listOf(listOf(3L, 2L), listOf(1L)), ids(FeedProjection.group(chain)))
        assertEquals(listOf(listOf(4L, 3L), listOf(2L), listOf(1L)), ids(FeedProjection.group(listOf(
            link(4, "android kotlin"), link(3, "android"), // exactly 1/2
            link(2, "android java compose"), // 1/4 with representative
            link(1, "android kotlin", "www.example.com")))))
    }

    @Test fun highestScoreWinsThenRepresentativeCaptureAndIdBreakTies() {
        val newer = link(5, "a b", at = 10)
        val older = link(4, "a c", at = 9) // 1/3: separate representatives
        val tie = link(3, "a b c", at = 8) // 2/3 against both
        val best = link(2, "a c", at = 7) // 1 against older, 1/3 against newer
        assertEquals(listOf(listOf(5L, 3L), listOf(4L, 2L)), ids(FeedProjection.group(listOf(older, best, tie, newer))))
        assertEquals(listOf(listOf(5L, 3L), listOf(4L)), ids(FeedProjection.group(listOf(
            newer, older.copy(capturedAt = 10), tie))))
    }

    @Test fun metadataFreeFallbackIsSeparatePerHostAndDoesNotSwallowTitledGroups() {
        assertEquals(listOf(listOf(6L, 4L, 2L), listOf(5L, 1L), listOf(3L)), ids(FeedProjection.group(listOf(
            link(1, "Android"), link(2, "!!!"), link(3, null, "other.example"),
            link(4, " "), link(5, "android"), link(6, null)))))
    }

    @Test fun multilingualTitlesActuallyGroupWithoutDiscardingNonLatinScripts() {
        val groups = FeedProjection.group(listOf(link(8, "ПРИВЕТ мир"), link(7, "привет мир новости"),
            link(6, "東京"), link(5, "東京"), link(4, "العربية"), link(3, "العربية"),
            link(2, "ação café"), link(1, "AC\u0327A\u0303O CAFE")))
        assertEquals(listOf(listOf(8L, 7L), listOf(6L, 5L), listOf(4L, 3L), listOf(2L, 1L)), ids(groups))
        assertTrue(groups.all { it.hasTitleKeywords })
        assertFalse(FeedProjection.group(listOf(link(9, "🎉"))).single().hasTitleKeywords)
    }

    @Test fun projectionUsesTheSameMembershipAndMakesScopedSearchFlatEvenFromArchive() {
        val rows = listOf(link(1), link(2).copy(archivedAt = 3), link(3).copy(deletedAt = 4))
        val main = FeedProjection.project(rows, FeedSelection())
        val grouped = FeedProjection.project(rows, FeedSelection(destination = FeedDestination.Grouped))
        assertEquals(main.links, grouped.links)
        assertEquals(main.links, grouped.groups.flatMap { it.links })
        val archived = FeedProjection.project(rows, FeedSelection(destination = FeedDestination.Archived))
        assertEquals(listOf(2L), archived.days.flatMap { it.groups }.flatMap { it.links }.map { it.id })
        val search = FeedProjection.project(rows, FeedSelection(destination = FeedDestination.Archived,
            searching = true, scope = SearchScope.All))
        assertEquals(listOf(2L, 1L), search.links.map { it.id })
        assertTrue(search.days.isEmpty())
        assertTrue(search.groups.isEmpty())
    }

    @Test fun metadataArrivalRegroupsWithoutLosingItemsOrChangingCaptureOrder() {
        val rows = listOf(link(3), link(2, "kotlin android"), link(1, "kotlin"))
        assertEquals(listOf(listOf(3L), listOf(2L, 1L)), ids(FeedProjection.group(rows)))
        val enriched = rows.map { if (it.id == 3L) it.copy(title = "Kotlin Android guide", previewStatus = "ready") else it }
        assertEquals(listOf(listOf(3L, 2L), listOf(1L)), ids(FeedProjection.group(enriched)))
        assertEquals(rows.map { it.id to it.capturedAt }, enriched.map { it.id to it.capturedAt })
        assertEquals(rows.map { it.id }.toSet(), FeedProjection.group(enriched).flatMap { it.links }.map { it.id }.toSet())
    }

    @Test fun mainAndGroupedCoverSameActiveIdsOnceWithStableOrderAcrossInputPermutations() {
        val rows = (1L..120L).map { id -> link(id, if (id % 4 == 0L) null else "topic ${id % 7}",
            "host${id % 3}.example", id / 4).copy(
            archivedAt = if (id % 5 == 0L) 999 else null, deletedAt = if (id % 11 == 0L) 1000 else null) }
        val main = FeedProjection.select(rows, SearchScope.Inbox)
        val expected = FeedProjection.group(main)
        val flattened = expected.flatMap { it.links }
        assertEquals(main.map { it.id }.toSet(), flattened.map { it.id }.toSet())
        assertEquals(main.size, flattened.size)
        assertEquals(main.size, flattened.map { it.id }.distinct().size)
        assertEquals(main.sortedWith(FeedProjection.recency), main)
        assertEquals(expected.map { it.links.first() }.sortedWith(FeedProjection.recency), expected.map { it.links.first() })
        expected.forEach { assertEquals(it.links.sortedWith(FeedProjection.recency), it.links) }
        repeat(30) { assertEquals(expected, FeedProjection.group(main.shuffled(kotlin.random.Random(it)))) }
    }

    @Test fun archivedPartitionsByLocalCaptureDayThenSimilarityAndNotArchiveTime() {
        val midnight = 1_767_225_600_000L // 2026-01-01T00:00:00Z
        val rows = listOf(link(1, "same title", at = midnight - 1), link(2, "same title", at = midnight),
            link(3, "same title", at = midnight + 1)).map { it.copy(archivedAt = 5 - it.id) } +
            link(4, "same title", at = midnight + 2) +
            link(5, "same title", at = midnight + 3).copy(archivedAt = 100, deletedAt = 101)
        val days = FeedProjection.archive(rows, TimeZone.getTimeZone("UTC"))
        assertEquals(listOf("2026-01-01", "2025-12-31"), days.map { it.day })
        assertEquals(listOf(listOf(3L, 2L), listOf(1L)), days.flatMap { ids(it.groups) })
        val shifted = FeedProjection.archive(rows, TimeZone.getTimeZone("GMT-03:00"))
        assertEquals(listOf("2025-12-31"), shifted.map { it.day })
        assertEquals(listOf(listOf(3L, 2L, 1L)), ids(shifted.single().groups))
    }

    @Test fun literalSearchIncludesEveryFieldUnicodeScopesAndBlankButNeverDeleted() {
        val rows = listOf(link(1, "CAFÉ 東京").copy(description = "Read\n \u0085Kotlin العربية", originalUrl = "https://example.com/#OriginalOnly"),
            link(2).copy(archivedAt = 5, description = "Привет мир", originalUrl = "https://example.com/100%_literal?q='x'"),
            link(3, "cafe").copy(deletedAt = 6), link(4).copy(canonicalUrl = "https://example.com/canonical-only"))
        fun search(q: String, scope: SearchScope = SearchScope.All) = FeedProjection.select(rows, scope, q).map { it.id }
        assertEquals(listOf(1L), search("cafe 東京"))
        assertEquals(listOf(1L), search("READ kotlin"))
        assertEquals(listOf(1L), search("عربية"))
        assertEquals(listOf(2L), search("привет", SearchScope.Archived))
        assertTrue(search("привет", SearchScope.Inbox).isEmpty())
        assertEquals(listOf(2L), search("100%_literal?q='x'"))
        assertTrue(search("100__literal").isEmpty())
        assertEquals(listOf(4L), search("canonical-only"))
        assertEquals(listOf(1L), search("#originalonly"))
        assertEquals(listOf(4L, 2L, 1L), search(" \u00a0 "))
        assertEquals(listOf(4L, 1L), search("", SearchScope.Inbox))
        assertEquals(listOf(2L), search("", SearchScope.Archived))
        assertTrue(search("東京 Read").isEmpty()) // never join separate fields
    }
}
