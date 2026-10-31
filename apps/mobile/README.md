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
coroutines 1.10.2. These stable versions support compile/target API 36 and
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
  and other schemes. IDN hosts become ASCII for identity. No network request is
  made; private/local URLs may be saved and opened by the user's chosen app.
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
- URL/host/date cards work offline, without images or metadata. No Internet
  permission is requested. Bounded safe metadata extraction, persistent retries,
  search, similarity, dark theme, and localization remain future work.

SQLite data persists across process restart/reboot; uninstall/clear-data removes
it. Android backup is disabled. No account, backend, sync, external AI, shared
package, or sibling checkout is involved.
