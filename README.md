# my-feed

Native Android personal link inbox: share a link, save it locally, and revisit it
in a quiet visual feed. No account, backend, sync, or AI service is required.

Milestones 1–3: native Kotlin/Compose application in [apps/mobile](apps/mobile).
Share or paste a link, browse local cards with background previews, archive/delete with undo,
and open links using Android. Capture needs no network or confirmation.

- [Product requirements](docs/product-handoff.md)
- [Architecture, assumptions, and milestones](docs/implementation-plan.md)

Stack: Kotlin, Jetpack Compose, Room/SQLite. Android 6.0 (API 23) or newer.
Opening a link never archives it. "Unseen" means still in the inbox.
Deletion uses soft delete with undo. Grouping uses exact host and normalized
title keywords. Duplicate shares refresh capture time and restore an
archived/deleted item to the inbox; URL identity rules preserve meaningful queries.
See [mobile behavior and build instructions](apps/mobile/README.md) and
[WNT-219 validation](runs/WNT-219.md) and [WNT-220 preview validation](runs/WNT-220.md).

```sh
make mobile-test   # JVM URL and Room tests
make mobile-build  # tests, lint, debug APK
make mobile-apk    # verified APK path
ANDROID_SERIAL=emulator-5590 make mobile-connected-test
```

Preview enrichment uses WorkManager with persistent retries, bounded HTTP(S)
metadata/image fetching, and local thumbnails. Offline capture never waits for
previews; unavailable previews retain a URL/host card with a retry action. Private
and reserved destinations can be saved/opened but are never fetched for previews.
See the [network policy and limits](apps/mobile/README.md#preview-enrichment).

Grouped contains the same inbox items exactly once, using deterministic title
similarity and a host fallback for missing titles. Archived groups within capture
dates. Local title/description/URL search offers Inbox, Archived and All scopes
and excludes deleted links. See [the precise rules](docs/search-and-grouping.md)
and [WNT-221 evidence](runs/WNT-221.md). Final acceptance, accessibility and
APK delivery evidence is in [WNT-222](runs/WNT-222.md). There are no cross-repository
dependencies, backend, login, or sync.
