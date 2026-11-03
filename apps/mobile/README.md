# LinkShelf branding

The app is displayed as **LinkShelf** from version 0.2.2. Its Android package,
local database, scheduled backup names and JSON format identifier remain stable
so existing installations and backups continue to work. See
[branding](../../docs/branding.md) for launcher and splash assets.

# Android app

One application module; Compose UI → ViewModel → repository → Room. Both share
and paste use the same URL parser and transaction. The share receiver saves
immediately, waits for the commit, shows a short result toast, then finishes.
It has a separate activity/task affinity so it returns to the sending app rather
than opening the feed. Capture runs in the application scope and is retained by
a ViewModel across receiver rotation; returning Back cannot cancel a save.

## Build and test

Use JDK 17 or 21. Set `ANDROID_HOME` to an Android SDK or put
`sdk.dir=/absolute/path/to/sdk` in an untracked `local.properties` here. Install:

```sh
sdkmanager 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
make -C ../.. mobile-build
make -C ../.. mobile-run
# Optional: choose a device/emulator or a different AVD
ANDROID_SERIAL=emulator-5556 ANDROID_AVD=my-feed-wnt-222 make -C ../.. mobile-run
```

The checked-in Gradle 8.13 wrapper verifies the distribution SHA-256. Versions
are pinned: AGP 8.13.2, Kotlin/Compose compiler 2.2.21, KSP 2.2.21-2.0.4,
Compose BOM 2025.10.01, Room 2.8.4, Activity 1.11.0, Lifecycle 2.9.4,
coroutines 1.10.2, WorkManager 2.11.2, OkHttp 4.12.0, jsoup 1.22.2. These pinned versions support compile/target API 36 and
minimum API 24. Official version references are in the validation record.

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug :app:assembleDebug
ANDROID_SERIAL=emulator-5590 ./gradlew :app:connectedDebugAndroidTest
adb -s emulator-5590 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5590 shell am start -W -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT 'Read https://example.com/story' \
  org.wastingnotime.linkshelf/.ShareActivity
```

Connected tests clear **LinkShelf's data on the selected test device**. Select a
dedicated emulator with `ANDROID_SERIAL`; the root shortcut requires it. CI runs
unit tests, lint, and API 35 emulator tests before publishing a debug APK artifact.
Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. It uses Android's local
debug signing key; release signing/distribution is not part of this milestone.

## Current semantics

- Capture uses the first valid HTTP(S) URL in text. It inspects at most 100,000
  characters and accepts URLs up to 8,192 characters. Surrounding whitespace,
  sentence-ending period/comma/semicolon and unbalanced closing brackets are
  treated as prose. Balanced parentheses inside URLs survive. Multiple URLs in
  one share intentionally capture only the first valid one.
- Reject credentials, invalid hosts/ports/percent escapes, whitespace, controls,
  and other schemes. IDN hosts become ASCII for identity. Capture makes no network
  request; private/local URLs may be saved and opened by the user's chosen app,
  but preview enrichment rejects them.
- Canonical identity lowercases scheme/host, removes default ports and fragments,
  and makes an empty path `/`. Preserve scheme distinctions, path case, escaping,
  query order, empty queries, values, and **all** query parameters (including
  tracking parameters). No redirects or remote canonical tags are consulted.
- A unique canonical URL plus insert/refresh in one Room transaction deduplicates
  concurrent captures. Re-share advances capture time (never backwards), clears
  archive/delete, preserves stable ID, the **first original URL**, and opening
  history. Lifecycle operations use the current database row, not a stale card.
- Main: `archivedAt == null && deletedAt == null`, newest capture first, descending
  ID on timestamp ties. Grouped contains those same items exactly once, grouped
  by exact host and normalized title-keyword Jaccard similarity (at least 0.5)
  against fixed newest-item representatives. Missing titles have a separate host
  fallback group. Groups and members sort by capture/ID recency. Archived shows
  archived/non-deleted links in capture-date sections, then similarity groups.
  Precise normalization, tie rules and limitations are in
  [search-and-grouping.md](../../docs/search-and-grouping.md).
- Search matches literal normalized substrings in title, description, original
  or canonical URL, with explicit Inbox / Archived / All chips. Soft-deleted rows
  never appear. Blank query lists the selected scope; pending/failed previews
  remain searchable by URL. Search defaults to the current feed's scope and
  shows chronological results labeled Inbox/Archived. Close search or Back
  returns to the selected feed. Controls survive activity recreation; process
  restoration of search controls is not promised. Projection runs locally off
  the UI thread; no schema/index migration or network request is required.
- Swipe right archives; swipe left soft-deletes. Buttons provide accessible
  alternatives; Archived also has Restore. A snackbar Undo restores only the
  previous archive/delete state. It preserves later opening history and refuses
  stale undo after a newer capture or lifecycle action. Undo lasts for the
  snackbar's lifetime (rotation/process death may dismiss it). Soft-deleted rows
  are retained for 30 days and can be restored or re-shared before permanent deletion.
- Tap dispatches `ACTION_VIEW` with the first original URL, including its fragment.
  Only scheme casing is lowercased for Android's case-sensitive intent filters;
  host spelling, explicit port, path, query, and fragment remain intact.
  Only a successful dispatch updates `lastOpenedAt`; it never changes capture,
  archive, or delete state. Handler errors show a message and leave the row alone.
- URL/host/date cards always work offline. Enriched text and cached thumbnails
  also remain available offline. Dark theme and localization
  remain future work.

SQLite data persists across process restart/reboot; uninstall/clear-data removes
it. Android backup is disabled. No account, backend, sync, external AI, shared
package, or sibling checkout is involved.

## Preview enrichment

Room commits capture before enqueueing WorkManager. `pending` rows act as a durable
outbox: app startup recovers unscheduled work, including old version 1 fallback
rows. The version 2 migration adds a nullable local image filename and preserves
all IDs, URLs, lifecycle state, and opening history. Enrichment updates only
preview fields, so it cannot reorder, archive, restore, or invalidate undo.

Work is unique per saved ID, requires `CONNECTED`, and uses exponential backoff
starting at 30 seconds. I/O/DNS/timeouts, HTTP 408/429 and 5xx retry up to four
attempts (including the first). Network constraints pause work offline without
consuming attempts. Unsafe URLs, other HTTP errors, unsupported/oversized content,
and empty metadata produce a fallback immediately. Failed cards offer **Retry
preview**; re-sharing a failed item also retries. Appended work serializes a retry
with any finishing worker; already enriched/deleted rows skip network access.
Android decides the exact execution time. Force-stopped apps resume on next launch.

Metadata prefers Open Graph, then Twitter, then HTML title/description. Text is
plain, entity-decoded and bounded (title 300, description 1,000, site name 150
characters). Missing/invalid OG images can fall back to Twitter image metadata.
Relative images resolve against the final validated page URL; HTML base,
canonical, refresh, scripts and frames are never followed or executed.
Missing/blocked/invalid images leave a readable text/URL card. Transient image
errors retry while retaining text, then settle on text after the attempt limit.
Recognized YouTube watch/short/live/embed links first use YouTube's public oEmbed
JSON endpoint, with a 64 KiB response limit; only title and thumbnail metadata
are read. Embed HTML is ignored. If it fails, bounded public HTML is tried.
There is no arbitrary oEmbed endpoint discovery or third-party preview service.

All network access uses one transport with these controls:

- HTTP(S) only, at most 8,192 URL characters; reject credentials, malformed URLs,
  scoped IPs, single-label/local hostnames, and local suffixes. Each redirect is
  independently validated (maximum three); HTTPS downgrade is rejected.
- Validate **every** A/AAAA result, reject the entire answer if any address is
  non-public, and pass those exact addresses to OkHttp's socket connection.
  No second resolution, ambient proxy, cookies, authentication, Referer, automatic
  redirect/retry, connection reuse, or HTTP/2 coalescing. Normal TLS hostname and
  certificate validation remains enabled.
- Deny IPv4 special-purpose/reserved/multicast ranges and IPv6 outside global
  unicast `2000::/3`, plus special ranges inside it (conservatively denying
  globally reachable special-purpose exceptions too). IPv4-mapped private,
  NAT64, 6to4, documentation, link-local, loopback and ULA destinations are blocked.
  Policy derives from IANA's special-purpose registries; it is a static policy
  that should be reviewed when those registries change.
- DNS wait 3 seconds, connect/read timeout 5 seconds, total call timeout 15 seconds
  per hop. At most four hops each for oEmbed (YouTube only), an HTML fallback,
  and one image: at most twelve calls for YouTube, eight otherwise. DNS uses two threads with eight queue slots;
  an uninterruptible platform lookup cannot spawn unbounded threads.
- HTML reads only its first 2 MiB, allowing metadata extraction from larger pages;
  images remain limited to 2 MiB. For images enforce both advertised length and
  streamed bytes, including chunked/unknown-length responses. Request identity
  encoding and reject compressed responses rather than risk decompression bombs.
- HTML/XHTML for pages; application/json only for the fixed YouTube oEmbed
  request. JSON and images reject oversized bodies rather than parsing prefixes.
  JPEG/PNG/WebP only for images. Inspect raster type
  and dimensions before decoding: maximum 4,096 per side and 8 million source
  pixels. Sample to at most 1,024 per side and store a bounded JPEG in app-private
  files. UI loads only that local thumbnail, never `imageUrl`; no SVG/WebView or
  remote executable content. Thumbnail files follow the retained soft-deleted
  collection and are removed during permanent-deletion cleanup.

Cleartext is enabled to support deliberately saved `http://` links through this
same safety policy. HTTP content has no transport authenticity. Private/local
bookmarks remain local until explicitly opened by the user in another app.
Safety test fixtures route validated public addresses to a local MockWebServer
using a test-only socket factory; production contains no unsafe-network bypass.
Connected UI tests use WorkManager's test scheduler with unmet network constraints
so they do not depend on external websites. The separate production smoke uses
an isolated emulator and real public HTTPS. Evidence and limitations:
[runs/WNT-220.md](../../runs/WNT-220.md).

Preview failures retain a reason in the local preview status, including page or
image request rejection, exhausted network retries, unsupported image decoding,
and missing metadata. Partial previews keep their useful text and offer Retry
preview. Text-only pages with no advertised image remain successful previews.
Existing ready cards with an image URL but no cached image also offer retry;
older failures have no retrospective diagnostic reason until retried.

## Sharing, appearance and backups

Each card's Share action sends its title and original URL through Android's share
sheet. More → Appearance offers System (default), Light and Dark; the choice is
saved on this device. More → About shows version, source and privacy information.

More → Backup exports a versioned `linkshelf` JSON document through Android's file
picker. Import validates the entire file (maximum 10 MB / 10,000 links), then asks
for confirmation and atomically adds missing canonical URLs. Existing links are
unchanged, including their lifecycle state. Exports include archived/deleted
links, titles, descriptions, source and capture/open timestamps. IDs, cached
images, theme and backup-folder permissions are device-local; imported links get
new IDs and previews are fetched again.

Choose a backup folder to enable daily WorkManager backups and queue an initial
backup. Back up now queues another backup; status reports the last successful
write and latest failure. Android may delay daily work. Seven dated JSON backups
are retained in the selected folder. Disabling stops scheduled work and releases
folder access without deleting completed backups. Keep exports somewhere private.

The folder-backup behavior was adapted from Sliding Tasks as an implementation
reference; there is no build or runtime dependency on that repository.

## UX update (0.2.0)

Inbox replaces the Main label. Inbox, Grouped and Archived preserve their scroll
positions independently; search focuses its input, offers Clear, and resets its
results position when the query or scope changes. Paste keeps the draft and
shows validation/storage errors inline, closing only after a successful save.

Cards emphasize the title and show a compact source/date row. Archive/Restore is
visible; the card menu contains Share, Delete and Link and preview details.
Technical preview causes and Retry preview live in details. Pending previews do
not imply the phone is offline. A missing cache can be fetched again; linked
articles/videos are not downloaded for offline reading.

More → Recently deleted lists soft-deleted links for 30 days from deletion.
Restore preserves the prior Inbox/Archived state and invalidates stale Undo
tokens. Restoring pending enrichment schedules it again. Each card also offers
Delete permanently (without undo). Cleanup runs on app startup/resume and in a daily
WorkManager job; Android may defer background execution. Existing expired records
are included. Cached thumbnails are cleaned up too. Previously exported backup
files are unchanged; importing expired deleted records triggers cleanup.

Backup is a full screen with selected folder, live work status and inline
operation results. Import is reviewed on that screen before confirmation and
shows new visible links, deleted records and existing URLs to skip. Appearance
uses selected radio rows; About includes app version and build number.


## Backup correctness (0.2.1)

Imports validate all rows before the transaction, require integer nonnegative
timestamps and string text fields, and reject duplicate canonical URLs or
unsupported format versions. Existing local rows are never overwritten. Exports
must pass the same validation and 10 MB / 10,000-link bounds before being written;
an over-limit export reports failure rather than publishing an unrestorable file.

Folder selection, disabling, and automatic/manual folder writes share a
process-wide lock. Retention happens after publication; a retention/status error
cannot delete the newly published backup. Manual document exports remain subject
to the selected Android provider's write semantics: interrupted writes may leave
an incomplete file, which import validation rejects. Cached images remain outside
the portable backup. Preview refresh preserves earlier/imported text when fresh
metadata is missing, and keeps an existing cached image when replacement fails.

Android application ID and code namespace: `org.wastingnotime.linkshelf`.
The earlier `org.wastingnotime.myfeed` development app is a separate Android
installation; export its backup and import it into LinkShelf to transfer data.
The legacy `my-feed` backup format remains readable for compatibility.

## Languages and favorites

The interface supports US English (`en-US`) and Brazilian Portuguese (`pt-BR`),
following Android's language preference with English as the fallback. Android 13+
also exposes both choices in the system's per-app language settings. Saved titles,
descriptions, URLs and stored technical preview diagnostics remain as captured.
Theme and settings keys stay independent of translated labels.

Tap a card's star to add/remove a favorite from Inbox, Grouped, Archived or search.
Grouped puts active favorites in its first group, newest capture first, with each
card appearing exactly once. Inbox and search keep chronological order; Archived
keeps date/similarity sections. The star survives restarting, re-sharing,
archive/delete/restore, preview updates and lifecycle Undo. Room schema 3 migrates
existing rows to unstarred without dropping data. Backups include the boolean
`isFavorite`; version-1 backups that omit it remain readable as unstarred.
