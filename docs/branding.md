# LinkShelf

LinkShelf is the user-facing name of the local-first link inbox. The repository
and Android application ID remain `my-feed` and `org.wastingnotime.myfeed`.
Renaming must not create a second installation or migrate/clear local storage.
Existing JSON exports retain their `my-feed` format marker; automatic backup
names also stay stable for retention compatibility. New manual exports use the
LinkShelf filename prefix.

## Mark and palette

The original vector mark combines two cards, a bookmark cutout and a short shelf.
No lettering is embedded. Forest green (#315C4B) and warm white (#F6F7F3) identify
the launcher. The themed icon uses the same geometry as a single-color mask.
The foreground fits the adaptive icon safe zone; Android supplies the launcher
mask. Older Android versions use a rounded-square vector fallback.

Assets live in `apps/mobile/app/src/main/res`: `ic_linkshelf_foreground`,
`ic_linkshelf_monochrome`, `ic_linkshelf_splash`, and `mipmap*/ic_launcher`.
These are original, code-native vectors, separate from the Material menu icons.

## Launch and share

MainActivity uses AndroidX core-splashscreen 1.2.0 and hands directly to the
inbox. There is no artificial wait, slogan, spinner or extra splash Activity.
Light launch colors are warm white/forest green; dark colors are charcoal
(#141A17)/sage (#A0D0B8). Android 12+ receives the saved appearance via
UiModeManager; earlier Android versions apply it to Activity resources.
ShareActivity uses a transparent theme without a branded launch screen and
still saves before finishing. The toast identifies LinkShelf.

References: [native splash migration](https://developer.android.com/develop/ui/views/launch/splash-screen/migrate),
[adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).
