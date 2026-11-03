# English/Portuguese and favorites validation

Date: 2026-11-03. Changes build on the existing, uncommitted LinkShelf package
migration in this working tree.

Implemented:

- English fallback and Brazilian Portuguese resources for navigation, cards,
  accessibility controls, settings, backup, paste and share feedback. The two
  catalogs contain the same 117 resource entries. Android 13+ per-app language
  settings advertise en-US and pt-BR; earlier versions follow device preferences.
  Captured content and stored technical preview diagnostics are not translated.
- Card star buttons persist an independent favorite flag. Grouped selects active
  favorites for its first group across hosts, newest capture/ID first, and applies
  existing similarity grouping only to the remaining links. Each active ID occurs
  once. Empty Favorites groups are omitted. Inbox/search chronology and Archived
  date/similarity grouping remain intact.
- Room schema 3 adds a non-null boolean with false as its migration default.
  Lifecycle actions, Undo, preview updates and duplicate captures preserve the
  current flag. JSON backups export the flag, validate its boolean type, and
  accept older files without it as unstarred.

Commands, from `apps/mobile`:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.wastingnotime.linkshelf.FavoritesLocaleFlowTest,org.wastingnotime.linkshelf.InboxFlowTest,org.wastingnotime.linkshelf.UxFlowTest
```

Results: 68 JVM tests and 16 connected UI tests passed; lint and both debug APK
builds passed. Connected tests used only the dedicated `my-feed-wnt-222` API 35
emulator, serial `emulator-5590`. They exercised both per-app locales, star
semantics/toggling, first-group rendering, recreation, Portuguese appearance and
paste controls, and existing capture/search/archive/delete/Undo flows. Unit tests
also exercised schema 1→3 and 2→3 upgrades against checked-in schemas, favorite
persistence through database reopen/re-share/preview/lifecycle/Undo, deterministic
group membership and backup round trips/legacy input/type validation.

No physical-device or external-app share validation was performed for this change.
APK: `apps/mobile/app/build/outputs/apk/debug/app-debug.apk`.
