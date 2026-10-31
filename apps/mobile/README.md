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
```

The checked-in Gradle 8.13 wrapper verifies the distribution SHA-256. Versions
are pinned: AGP 8.13.2, Kotlin/Compose compiler 2.2.21, KSP 2.2.21-2.0.4,
Compose BOM 2025.10.01, Room 2.8.4, Activity 1.11.0, Lifecycle 2.9.4,
coroutines 1.10.2, WorkManager 2.11.2, OkHttp 4.12.0, jsoup 1.22.2. These pinned versions support compile/target API 36 and
minimum API 23. Official version references are in the validation record.

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug :app:assembleDebug
ANDROID_SERIAL=emulator-5590 ./gradlew :app:connectedDebugAndroidTest
adb -s emulator-5590 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5590 shell am start -W -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT 'Read https://example.com/story' \
  org.wastingnotime.myfeed/.ShareActivity
```

Connected tests clear **my-feed's data on the selected test device**. Select a
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
  ID on timestamp ties. Grouped is the same ordered inbox with an explicit
  fallback notice; similarity is milestone 3. Archived shows archived/non-deleted
  links in capture order; similarity/date sections also follow in milestone 3.
- Swipe right archives; swipe left soft-deletes. Buttons provide accessible
  alternatives; Archived also has Restore. A snackbar Undo restores only the
  previous archive/delete state. It preserves later opening history and refuses
  stale undo after a newer capture or lifecycle action. Undo lasts for the
  snackbar's lifetime (rotation/process death may dismiss it). Soft-deleted rows
  persist with no automatic purge and can be recovered by re-sharing.
- Tap dispatches `ACTION_VIEW` with the first original URL, including its fragment.
  Only scheme casing is lowercased for Android's case-sensitive intent filters;
  host spelling, explicit port, path, query, and fragment remain intact.
  Only a successful dispatch updates `lastOpenedAt`; it never changes capture,
  archive, or delete state. Handler errors show a message and leave the row alone.
- URL/host/date cards always work offline. Enriched text and cached thumbnails
  also remain available offline. Search, similarity, dark theme, and localization
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
canonical, refresh, scripts, frames and oEmbed are never followed or executed.
Missing/blocked/invalid images leave a readable text/URL card. Transient image
errors retry while retaining text, then settle on text after the attempt limit.
No special YouTube service or API is used; blocked/script-only pages fall back.

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
  per hop. At most four hops each for one HTML page and one image, so a worker
  performs at most eight calls. DNS uses two threads with eight queue slots;
  an uninterruptible platform lookup cannot spawn unbounded threads.
- HTML maximum 512 KiB; image maximum 2 MiB. Enforce both advertised length and
  streamed bytes, including chunked/unknown-length responses. Request identity
  encoding and reject compressed responses rather than risk decompression bombs.
- HTML/XHTML only for metadata; JPEG/PNG/WebP only for images. Inspect raster type
  and dimensions before decoding: maximum 4,096 per side and 8 million source
  pixels. Sample to at most 1,024 per side and store a bounded JPEG in app-private
  files. UI loads only that local thumbnail, never `imageUrl`; no SVG/WebView or
  remote executable content. Thumbnail files follow the retained soft-deleted
  collection and are removed by uninstall/clear-data.

Cleartext is enabled to support deliberately saved `http://` links through this
same safety policy. HTTP content has no transport authenticity. Private/local
bookmarks remain local until explicitly opened by the user in another app.
Safety test fixtures route validated public addresses to a local MockWebServer
using a test-only socket factory; production contains no unsafe-network bypass.
Connected UI tests use WorkManager's test scheduler with unmet network constraints
so they do not depend on external websites. The separate production smoke uses
an isolated emulator and real public HTTPS. Evidence and limitations:
[runs/WNT-220.md](../../runs/WNT-220.md).
