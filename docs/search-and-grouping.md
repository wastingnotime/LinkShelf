# Local search and grouping (milestone 3)

These are derived views of the same Room collection. No group membership or
search index is persisted. Preview enrichment, capture and lifecycle changes
recompute the views locally; no request or classification service is involved.

## Ordering and membership

Main and Grouped select `deletedAt == null && archivedAt == null`. Archived
selects `deletedAt == null && archivedAt != null`. Every selected ID occurs once.
Within each group and in flat views, ordering uses capture time descending, then
stable ID descending, matching Main's existing rule. Grouped places its Favorites
group first when any active links are starred, then applies similarity grouping
to the remaining active links. A favorite appears only in Favorites; it is never
duplicated in a similarity group. Favorites can span hosts and titles. Opening and archive time do not affect ordering.

A card's star toggles its persisted `isFavorite` flag independently of opening,
archive/delete state and lifecycle Undo. Re-sharing, restoring and preview
updates preserve the flag. Archived retains capture-day/similarity grouping;
archived/deleted favorites stay out of Grouped. Removing the last active favorite
removes the Favorites heading. Backup exports preserve favorites; older backups
without the flag import as unstarred.

## Similarity algorithm

1. Normalize title with Unicode NFKC, lowercase using `Locale.ROOT`, decompose
   with NFD and remove Unicode combining marks. Keywords are the set of maximal
   consecutive Unicode letter/number sequences. Repeated keywords count once;
   punctuation/emoji separate tokens. Keep single-character tokens; no language
   stop list, stemming, translation or script-specific segmentation. For example
   `CAFÉ café Android!` becomes `{cafe, android}`. Unspaced CJK text is one token.
2. Process items in the capture/ID order above. A new group has a fixed
   representative: its first (newest) item. Only groups with the **exact stored
   normalized host** are candidates (no www/subdomain, scheme or port merging
   beyond the existing stored-host semantics).
3. For nonempty keyword sets, score against each candidate representative using
   Jaccard similarity: intersection size / union size. Join the highest scoring
   group if the score is **at least 0.5**, otherwise start a new group. At equal
   scores choose the representative with the newest capture, then greatest ID.
   Do not compare against other members, merge existing groups or use transitive
   chains. Thus A~B and B~C alone cannot force C into A's group.
4. Null, blank or tokenless titles use a separate **Untitled links**
   bucket per exact host. They never act as wildcards joining titled groups.
   Description, URL path and preview status do not influence similarity.
5. Members remain in capture/ID order. Groups sort by their newest member using
   that same order. Group headings show host plus the representative's title (or
   the explicit fallback label); they describe the rule rather than inventing
   topic names. A singleton is a valid group.

Input order does not affect the result. New captures, re-shares, lifecycle changes
or newly enriched titles can change representatives/membership. This is a
deterministic heuristic, not semantic similarity; no cross-language equivalence
or useful segmentation of every writing system is claimed.

Archived first partitions by **capture calendar day in the device time zone**,
newest date first, then applies the same similarity algorithm independently in
each day. A title/host can therefore appear in different date sections. Archive
timestamps never replace capture dates. Time-zone changes take effect on the
next projection (including returning to the screen); tests inject a time zone.

## Search

Search has explicit Inbox / Archived / All scope chips, defaulting to Inbox from
Main/Grouped and Archived from Archived. While search is open it shows a flat
capture/ID-ordered result list, with an Inbox/Archived label on each card. Exiting
search returns to the selected feed view. Back closes search before navigating
from Grouped/Archived to Main. Query/scope/view survive activity recreation via
the ViewModel; process-restoration of search controls is not promised.

Normalize both query and fields with the same Unicode/case/mark normalization
above, collapse Unicode whitespace to one space and trim. Match the whole query
as a **literal substring of any individual title, description, original URL or
canonical URL**. No wildcard, regex, SQL or FTS query syntax, ranking, token-AND
or cross-field concatenation. A blank query lists the entire selected scope;
punctuation such as `%`, `_` and quotes is literal. Soft-deleted rows are excluded
in every scope, even if a caller supplies them. Failed/pending previews remain
searchable by URL. Metadata arrival updates title/description matches immediately.

For this personal MVP collection, scan the current Room snapshot on
`Dispatchers.Default`, alongside grouping. SQLite FTS was evaluated: its
[word-token/prefix query semantics](https://www.sqlite.org/fts3.html) do not
directly implement these literal Unicode substring rules. A derived index,
migration and synchronization triggers are deferred until measured collection
size requires them. Search is linear in text size; worst-case representative
grouping is quadratic within a host/day. Neither computation runs on the UI
thread. No database schema or existing preview/lifecycle policy changes.
