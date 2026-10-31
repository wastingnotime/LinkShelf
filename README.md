# my-feed

Native Android personal link inbox: share a link, save it locally, and revisit it
in a quiet visual feed. No account, backend, sync, or AI service is required.

Milestone 1: native Kotlin/Compose application in [apps/mobile](apps/mobile).
Share or paste a link, browse local fallback cards, archive/delete with undo,
and open links using Android. Capture needs no network or confirmation.

- [Product requirements](docs/product-handoff.md)
- [Architecture, assumptions, and milestones](docs/implementation-plan.md)

Stack: Kotlin, Jetpack Compose, Room/SQLite. Android 6.0 (API 23) or newer.
Opening a link never archives it. "Unseen" means still in the inbox.
Deletion uses soft delete with undo. Grouping uses deterministic domain and
normalized title keywords in a later milestone. Duplicate shares refresh capture time and restore an
archived/deleted item to the inbox; URL identity rules preserve meaningful queries.
See [mobile behavior and build instructions](apps/mobile/README.md) and
[WNT-219 validation](runs/WNT-219.md).

```sh
make mobile-test   # JVM URL and Room tests
make mobile-build  # tests, lint, debug APK
make mobile-apk    # verified APK path
ANDROID_SERIAL=emulator-5590 make mobile-connected-test
```

Grouped currently shows Main's inbox with a visible fallback notice. Metadata,
WorkManager retries, similarity grouping, and search belong to later milestones.
There are no cross-repository dependencies, backend, login, or network permission.
