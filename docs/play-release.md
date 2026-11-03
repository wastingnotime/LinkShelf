# LinkShelf Google Play release

Android application ID and Kotlin namespace: `org.wastingnotime.linkshelf`.
The repository remains `wastingnotime/my-feed`.

Release setup reference: Sliding Tasks in the Wasting No Time developer account,
inspected in Play Console on 2026-11-02. This is a configuration reference only;
there is no source or runtime dependency on Sliding Tasks.

Use the internal testing track, the existing “Sliding Tasks internal testers”
email list (4 testers at inspection), the App application type, and Productivity
category. External marketing is enabled in the reference app. Keep the initial
LinkShelf release internal; Sliding Tasks' production availability is not part
of this release request. Support email and website must describe LinkShelf;
Sliding Tasks' product-specific contact details are not LinkShelf's contacts.

LinkShelf was created as an English (United States), free app after explicit
confirmation of the Developer Program Policies and US export declarations.
Play app ID: `4972471744137829654`. Internal track: `4700463757859557549`.
The existing tester list is saved. Productivity is saved as a store-settings
change pending review; external marketing is enabled.

A signed release bundle for version 0.2.2 (4) was built and uploaded. Google
Play rejected it because automatic installer protection requires minSdk 24,
while the first bundle supported minSdk 23. The user requested matching Sliding Tasks, whose automatic protection is
active. LinkShelf therefore keeps installer protection enabled and raises its
minimum SDK to 24 (Android 7.0). The replacement signed bundle was built with minSdk 24; all 63 tests and lint passed.

Release signing uses environment variables `LINKSHELF_UPLOAD_STORE` and
`LINKSHELF_UPLOAD_PASSWORD`, alias `upload`. The dedicated upload keystore and
password are held under `~/.local/share/linkshelf/signing/` with private file
permissions; neither is committed. Back up that directory securely before
moving to another build machine. Build with `:app:bundleRelease` after supplying
the signing environment. An unsigned bundle is produced without that environment.

Uploaded bundle SHA-256:
`1f6240964a68d8c7ba5403d6b95ebd2e2eb1a8dea1a6036956302f70dbbc7fbb`.
`bundleRelease` and its release lint checks succeeded; `jarsigner -verify`
confirmed the upload signature.

Changing the application ID creates a separate Android installation from the
previous `org.wastingnotime.myfeed` development app. Use its export and the new
app's import flow to transfer saved links. Do not uninstall or clear the old app
before transferring data. New exports identify as `linkshelf`; legacy `my-feed`
JSON exports and automatic backup names remain readable.

Validation after the namespace rename: `make mobile-build` succeeded (63 JVM
and Robolectric tests, lint, and debug APK assembly). APK metadata confirms
`org.wastingnotime.linkshelf`, version 0.2.2 (4), target SDK 36.

## Published internal release

On 2026-11-02, Play Console confirmed the track **Active** and release
**4 (0.2.2) internal test** **Available to internal testers**. This is an internal
release only, not a production rollout. Play uses the temporary store name
`org.wastingnotime.linkshelf (unreviewed)` until app setup and review are complete.

Tester opt-in: https://play.google.com/apps/internaltest/4700463757859557549

Console release:
https://play.google.com/console/u/0/developers/8460748010140158851/app/4972471744137829654/tracks/4700463757859557549/releases/1/details

Installer check stays enabled, matching the observed Sliding Tasks setting.
The accepted bundle has minSdk 24 and targetSdk 36. Google Play's only release
warnings were absent optional deobfuscation and native debug-symbol files.
The app is not minified; native libraries come from dependencies.
