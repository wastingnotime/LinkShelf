# Sharing, backups and appearance validation

Implemented card title/URL sharing; More menu with About, System/Light/Dark
appearance, manual JSON export/import and daily folder backups with seven-file
retention. Sliding Tasks folder backup implementation served as a source
reference, adapted locally without a sibling build/runtime dependency. Imported
links merge atomically by canonical URL, preserve lifecycle timestamps, and
regenerate preview caches; existing links are unchanged.

Validation: `ANDROID_HOME=/home/henrique/tools/android-sdk make mobile-build`
passed: 55 JVM tests, zero failures/errors, lint and debug APK assembly.
FeedBackupTest covers portable lifecycle data, importing into another database,
keeping existing rows, and malformed/unsupported files. Preview recovery tests
also pass with the earlier failure-reason changes.

No device installation, file-picker interaction, external share target, or
scheduled provider write was exercised in this change. Real-device UI and
provider behavior remain to be checked. APK:
`apps/mobile/app/build/outputs/apk/debug/app-debug.apk`.
