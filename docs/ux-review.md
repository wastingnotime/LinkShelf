# UX review — 2026-11-01

Scope: current Android implementation, product handoff, prior acceptance evidence,
and the preview problems observed in this session. The connected phone was locked
during this review; current layout, clipping, contrast and touch behavior were
not visually verified. Findings below distinguish code-confirmed behavior from
proposed design improvements. No application behavior changed during the review.

The product has a useful foundation: immediate share capture, local storage,
independent open/archive behavior, undo, and searchable saved links. The next
iteration should make links easier to recognize and triage, and make save/backup
outcomes trustworthy. Preview availability itself remains a product problem;
clearer error copy alone does not restore missing titles or thumbnails.

## Prioritized findings

### 1. High — invalid paste dismisses the form and loses the input

`FeedScreen.kt:166` calls asynchronous capture and immediately closes the dialog.
Any nonblank input enables Save; validation and storage errors arrive later as a
snackbar. The dialog's input state is scoped to its conditional composition.

Keep the form and entered text until persistence succeeds. Show invalid-link
errors inline; distinguish invalid input from a storage failure. Autofocus the
field and offer the keyboard action to save. Acceptance: paste invalid text,
press Save, correct it in place, then save successfully without reopening.

### 2. High — backup outcomes require guesswork

`AppSettings.kt:32` sends operation results to the feed snackbar while the modal
remains open. The backup UI has no busy state, selected-folder label, or live work
observation. Back up now reports that work was queued, then the displayed result
stays stale until Refresh status or reopening. Import adds a second dialog above
the backup dialog, without a preview of new/duplicate counts.

Use a dedicated Backup screen with manual export/import and automatic backup
sections. Show selected folder, running state, last successful backup, and a
specific actionable error inline. Observe job completion automatically. Before
import, show links to add and duplicates to skip; report visible and deleted
records distinctly. Preserve pending import/form state across recreation.
Acceptance: remain on Backup during a successful and a failed write and see the
outcome without manually refreshing; an invalid file has a specific error.

### 3. High — preview state confuses availability with content recognition

Observed in this session: titleless YouTube/Google Play cards, HTTP 403 from
Forbes/Oracle, and successful text-only previews. `FeedScreen.kt:230` always shows
preview status; pending work says Saved offline even when online. Raw diagnostic
messages become card copy. `produceState` starts with a null bitmap, so an image
still loading can briefly be described as unable to load. A stored image path
whose file cannot be loaded offers no retry through the existing condition.

Show a normal, useful text-only card when an image is absent. Use neutral pending
copy (Fetching preview), a small failure affordance (Preview unavailable), and
Details for the cause and retry. Keep technical causes available. Distinguish
loading, loaded and failed cache reads and allow recovery for a missing cache.
Do not suggest that the linked article/video itself is saved offline.

Separately, improve title/thumbnail retrieval for common providers. This needs
its own investigation and real-device verification; a visual redesign does not
resolve HTTP refusals or metadata omitted from the fetched page prefix.

### 4. Medium — card hierarchy favors chrome over the saved item

`FeedScreen.kt:219` gives the source titleMedium typography while the article
gets bodyLarge. Each card can include an image, source plus host, title,
description, raw URL, full timestamp, status, retry, and three action buttons.
This is a structural density issue; the exact visible card count needs a current
screenshot.

Make the title primary; use one secondary source/date row, an optional image,
and a short description. Put raw URL, exact timestamp and diagnostics in details.
Keep Archive readily accessible and use a labeled overflow menu for secondary
Share/Delete/Preview details actions. Preserve explicit accessible alternatives
to swipes. Do not rely on an image for recognizing a link.

### 5. Medium — navigation and search add avoidable friction

Main refers to the same collection that search calls Inbox. Grouped is an
alternative organization of that collection but occupies a peer destination.
The header says newest first in both views although groups interrupt global
recency. The top bar contains three text actions; narrow/large-font layout needs
fresh verification after adding More. Search reveals a field without explicit
focus, and changing `FeedSelection` recreates the list via `key(showing)`, losing
browsing position across view/search switches.

Rename Main to Inbox; consider grouping as a view choice within Inbox while
keeping Archive a destination. Use recognizable labeled icons for search/menu
and a clear Add link action. Focus search when opened, provide clear-query,
preserve per-view scroll positions, and retain the explicit search scope.
Acceptance: switching to Archive and back restores the previous inbox position;
opening search allows immediate typing.

### 6. Medium — delete recovery ends with the snackbar

Deletion is soft and backed up, but the app has no trash/recovery view. Once Undo
expires the user cannot retrieve the item through the UI. This is consistent
with the original MVP scope, but it becomes harder to explain now that deleted
records are included in backups and imported while still hidden.

Decide whether to add a small Recently deleted screen with Restore, or explicitly
retain undo-only behavior and explain deleted-record backup semantics. Prefer
making Delete a secondary action and preserve immediate Undo. Do not silently
change deletion or retention policy as part of visual cleanup.

### 7. Low — Appearance and About need clearer structure

Appearance uses text buttons with a checkmark rather than a selected radio row.
About is consecutive text blocks without section spacing. The version remains
0.1.0 across the installed iterations, which makes support conversations harder.

Use full-width radio rows with selected semantics for System/Light/Dark. Give
About distinct app/version, purpose, privacy and source sections. Include a
useful build identifier. Verify both themes and large text, not just persistence
of the selected preference.

## Suggested sequence

1. Fix paste error recovery, honest preview loading states, and missing-cache retry.
2. Redesign card hierarchy and Inbox/search navigation together.
3. Replace the Backup modal with a task screen and observable operation outcomes.
4. Improve common-provider previews as a separate capability change.
5. Validate current screens on the unlocked phone in light/dark themes, large
   text, empty/populated views, slow previews, share sheet and backup file pickers.

Keep fast share capture, no account requirement, opening without archiving,
local search, and Undo. No backend or shared platform dependency is proposed.

References consulted: Android's [search component guidance](https://developer.android.com/develop/ui/compose/components/search-bar)
and [focus guidance](https://developer.android.com/develop/ui/compose/touch-input/focus).
These support native component/focus choices; the priorities above are judgments
based on this app's implementation and the user's observed problems.

## Implementation follow-up

The scoped UX changes were implemented in version 0.2.0; see
[runs/ux-020.md](../runs/ux-020.md) for validation and remaining limits.
Grouped remains an explicitly labeled peer view, and provider-specific preview
retrieval remains a separate investigation.

The icon follow-up uses bundled Material Symbols Rounded vectors: inbox, grid,
and archive navigation icons have outlined/selected filled states; paste uses
add-link. Settings and card menus pair icons with their existing text labels.
Backup uses a local-save symbol. Decorative icons have no duplicate accessibility
description, and Compose supplies theme-aware colors. Upstream attribution and
the Apache-2.0 license ship in the app's assets/licenses directory.
