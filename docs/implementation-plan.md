# Implementation plan

## Authority and boundaries

The handoff in product-handoff.md is the product source. This plan records initial
implementation decisions, not validated application behavior. Single Android
application under apps/mobile; no multi-channel monorepo, MCP server, or backend.
The app owns local data directly and delegates sharing/opening to Android platform
services. No server adapter or synchronization contract is needed.

Use one app module, Compose UI, a ViewModel/repository boundary, Room storage,
and WorkManager for preview retries. Avoid speculative module or shared-package
extraction. Main, Grouped, and Archived query the same collection.

## Initial decisions

- Inbox means non-archived and non-deleted, regardless of lastOpenedAt.
- Soft delete persists deletedAt; undo restores the previous lifecycle state.
  No automatic permanent purge in MVP.
- Duplicate capture atomically reuses a canonical URL, refreshes capturedAt, and
  clears archivedAt/deletedAt. Preserve originalUrl and lastOpenedAt. Enforce a
  unique canonical URL; test concurrent capture.
- Normalize HTTP(S) scheme/host case, default ports, empty path, and fragments.
  Preserve path case, scheme distinctions, query order/values, and unknown query
  parameters. Strip only explicitly documented tracking parameters. Do not use
  remote canonical tags or redirects as deduplication authority.
- Group by exact host plus deterministic normalized title keyword overlap;
  document tokenization, threshold, tie breaking, and URL-only fallback before
  implementation. Groups sort by latest capture; items sort newest first with
  stable ID tie breaking. Domain-only grouping is a fallback, not the sole rule.
- Preview failure cannot fail capture. Fetch HTTP(S) only, reject credentials and
  local/private/reserved destinations, validate every redirect and resolved
  address, bound redirect count, timeouts and response bytes. Apply equivalent
  controls to preview images. Never execute remote content. Initial slice uses
  URL/host cards; milestone 2 adds the enrichment policy documented in
  apps/mobile/README.md.
- Search offers explicit Inbox / Archived / All scopes and excludes deleted
  rows. Match title, description, and URL locally; evaluate Room FTS when adding it.

Milestone 3's precise normalization, Jaccard threshold, representative/tie rules,
metadata-free fallback, archived capture-day sections and literal scoped search
are specified in [search-and-grouping.md](search-and-grouping.md). Establish these
rules and their tests before implementing the derived views.

## Milestones and verification

1. Vertical slice: reproducible Android build, Room model, share receiver and
   paste, fallback cards, lifecycle actions/undo, ACTION_VIEW without archiving.
   Test URL extraction/canonicalization, duplicates, lifecycle persistence,
   process recreation, offline capture. Provide Main/Grouped/Archived navigation;
   similarity and metadata are delivered in subsequent milestones.
2. Preview enrichment: bounded safe metadata fetch, persistent retry, offline and
   failed states, OG/Twitter metadata and image fallback. Test redirects, unsafe
   targets, limits, malformed content, and retry recovery.
3. Search and grouping: documented heuristic, explicit search scopes, stable
   ordering, archived grouping, no lost/duplicated items. Test representative
   multilingual titles, empty metadata, and lifecycle/filter interactions.
4. Delivery validation: accessibility/back/swipe feedback and edge states; CI
   runs unit tests before debug APK upload. Record emulator/device versions and
   real Chrome, YouTube, WhatsApp shares where available. Do not claim real-app
   validation without evidence. Provide mobile-test/build/apk root shortcuts.

Each implementation issue executes in a dedicated worker worktree. Record test
commands and limits in runs/ and review PR evidence before accepting completion.
Dependency versions, minimum Android version, and SDK availability are resolved
by the implementation worker from official Android documentation and local tools.
