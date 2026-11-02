# Backup correctness and preview investigation — 2026-11-02

Delivered version 0.2.1 / build 3. Scope: import/export data preservation,
scheduled backup publication/concurrency, and the saved cards whose preview
images were unavailable. This was a correctness review, not a repository-wide
security audit.

## Findings and changes

| Finding | Consequence | Change |
| --- | --- | --- |
| The HTML prefix stopped at 512 KiB | Live probes found YouTube OG metadata around byte 776,250 and Google Play around 1,053,594, beyond the parser's input | Bounded HTML prefix increased to 2 MiB |
| YouTube used only its heavyweight public HTML | Titles and thumbnails could be missed despite being available through a compact public endpoint | Recognized watch/shorts/live/embed and youtu.be links use fixed first-party oEmbed JSON, with HTML fallback |
| Empty refresh metadata replaced imported text | Successfully restored descriptions/titles could disappear during enrichment | Merge missing fresh fields with existing metadata; preserve an existing cache when replacement fails |
| JSON numeric/text getters coerce values | Invalid timestamp types, fractional versions, and inconsistent duplicate records could be accepted | Strict integer/nonnegative timestamps, string fields, exact format version, duplicate-canonical rejection before transaction |
| Export had no equivalent importability check | Over-limit exports could report success but fail to restore | Validate generated export with the same parser before writing |
| Manual/periodic folder backups and folder changes were independent | Retention/status could race or refer to an obsolete configuration | Serialize configure, disable and folder writes using a shared process lock |
| Cleanup deleted the pending document even after successful rename | A later retention failure could delete the newly published backup | Track publication; cleanup deletes only an unpublished temporary document |

## Preview verification

Read-only workstation probes and an explicit, opt-in phone diagnostic exercised
actual requests through production SafeHttp, metadata parsing and image decoding.
The phone diagnostic used a disposable database/cache first, then queued normal
preview retries for the successful image candidates. It did not clear the user's
database or alter link lifecycle fields.

Seven previously missing thumbnails were recovered and persisted on the phone:
five YouTube cards, Google Play, and one Google share link. All 25 records were
retained; SQLite integrity was ok. Original/canonical URLs, capture/archive/delete/
opening timestamps and revisions matched the pre-update snapshot exactly.

Remaining observed causes:
- Forbes and Oracle: HTTP 403 on the phone.
- One Google share link resolves to `https://basecamp.com/shapeup/shape-up.pdf`
  with application/pdf; this app's preview parser supports HTML, not PDF rendering.
- Jobescape advertises an image on myjobescape.com; the hostname failed DNS
  resolution in the workstation probe, and the phone reported an image network
  failure.
- Humble Bundle, Healthboost and The Last Slice produced useful text but no
  advertised preview image in the fetched metadata. Humble includes image width/
  height tags without an image URL; those dimensions do not identify an image.

No cookies, login sessions, third-party preview services, script execution or
403-bypass mechanisms were added. oEmbed is restricted to the recognized YouTube
endpoint, with a 64 KiB strict JSON limit; embedded HTML is ignored. Thumbnails
retain existing DNS/redirect/content/dimension checks and the 2 MiB image bound.
The bounded HTML fallback may still miss metadata beyond 2 MiB.

Reference: [oEmbed specification and YouTube example](https://oembed.com/).
The implementation uses only inert title/thumbnail fields, never embed HTML.

## Validation and limits

- 61 JVM tests passed, zero failures/errors; lint and debug APK assembly passed.
- Tests cover recognized/rejected YouTube URL shapes, oEmbed fields and HTML
  fallback, metadata beyond the former cutoff, preserving imported metadata,
  malformed/coerced backup fields, duplicate URLs and no partial import writes.
- Production live preview diagnostic on phone ZF5254WKJ8 passed, including saved
  thumbnail checks and unchanged lifecycle fields.
- Automatic backup concurrency/publication fixes were reviewed statically; a
  storage-provider fault-injection matrix was not run. The lock covers this
  application's single process; no multi-process backup execution is configured.
- Manual document export inherits the selected provider's stream semantics;
  interrupted writes can leave incomplete documents, which import rejects.
- Portable backup intentionally excludes cache files and device-local preferences,
  IDs and folder grants. Imports merge missing canonical URLs, preserving existing
  records rather than overwriting them. Oversized exports now fail explicitly;
  automatic file splitting is not implemented.

Private snapshots and raw device data remain outside the repository in the user's
local state directory. Opt-in diagnostic tests are skipped by default and never
clear the user's database.

Final backup device check (2026-11-02): the inbox had grown to 30 records since
the preview validation. BackupDeviceDiagnosticTest exported a consistent Room
transaction snapshot, restored it into a disposable database, and compared every
portable field with the source. All 30 matched; a repeated import added zero
records, and an invalid late row left the restored database unchanged. The
corrected diagnostic passed in 0.651 seconds. The diagnostic app was removed
afterwards; original phone data was read-only during this check.
