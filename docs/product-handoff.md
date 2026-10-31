# my-feed — Codex handoff

## Product intent

Build **my-feed**, a native Android, local-first personal link inbox. The user currently sends links from Chrome, YouTube, WhatsApp, and other apps to Telegram to revisit later. That involves unnecessary taps and mixes all saved links into a chat.

**Core promise:** Capture a link through Android Share, then revisit and triage it in a visual feed with minimal friction.

This is a content inbox, not a social network, read-it-later article downloader, or complex bookmarking organizer.

## Platform and constraints

- **Native Android only.** Do not implement Flutter, a PWA, or a web frontend.
- Suggested stack: Kotlin, Jetpack Compose, Room/SQLite, WorkManager, Android Sharesheet (`ACTION_SEND`).
- Local-first; **no backend, login, or sync required for MVP**.
- Avoid unnecessary architecture and third-party services.
- Favor a small, testable vertical slice before expanding functionality.

## Primary user flows

### 1. Capture

1. User selects Share in Chrome, YouTube, WhatsApp, or any app sharing a URL/text.
2. Android Sharesheet lists **my-feed** as a target.
3. Selecting my-feed stores the URL immediately and returns to the originating app with minimal UI interruption.
4. Preview metadata is fetched asynchronously; failure does not prevent saving.
5. Re-sharing an existing canonical URL must not silently create an unnecessary duplicate. Define and test a sensible policy (e.g. keep existing item and update capture timestamp).

Also support pasting a URL directly in my-feed.

### 2. Browse and triage

- **Main:** unarchived, non-deleted items, newest captured first.
- **Grouped:** the same unarchived items, grouped by similarity, with recent items first within groups.
- **Archived:** archived items, grouped by similarity and date.
- Each item is a social-preview-style card: image (if available), title, description, domain/source, and capture date.
- **Swipe left:** delete (prefer soft-delete or undo to protect against accidental gestures).
- **Swipe right:** archive.
- **Tap:** open original URL using Android's normal link-opening mechanism.
- **Opening a link must not automatically archive it.** Track `lastOpenedAt` independently, if useful.
- Provide quick search across saved content, including archived items, with a clear scope/filter.

### 3. Preview fallback

- Fetch Open Graph / Twitter Card metadata where available; support YouTube metadata via a suitable public mechanism if needed.
- For unavailable, blocked, or malformed metadata, show a useful fallback card with host/domain and URL.
- Do not assume all shared text is a valid URL; extract and validate URLs safely.
- Network requests must have timeouts, bounded downloads, and appropriate safety controls; never execute remote content.

## Domain model (initial hypothesis)

`SavedLink`

- `id`: local stable ID
- `originalUrl`: URL received from share
- `canonicalUrl`: normalized URL for deduplication
- `title`: nullable
- `description`: nullable
- `imageUrl`: nullable
- `siteName`: nullable
- `capturedAt`: timestamp
- `archivedAt`: nullable timestamp
- `deletedAt`: nullable timestamp
- `lastOpenedAt`: nullable timestamp
- `previewStatus`: pending / ready / failed

**Important semantic distinction:** opening is an event, not proof that the content has been consumed. `archivedAt` and `lastOpenedAt` must be independent.

The user's term **unseen** is interpreted provisionally as **still in the inbox**, not necessarily never opened. Confirm before introducing a separate read/unread state.

## Grouping and search

- Grouping must not require an LLM or external API for MVP.
- Start with deterministic grouping (e.g. domain, keywords/title similarity) and design it so that a more sophisticated similarity strategy can be introduced later.
- Do not add manual tags, folders, or category management in the MVP.
- Use SQLite FTS if appropriate for local title, description, and URL search.
- Make grouping rules and ordering explicit and testable; avoid presenting arbitrary group labels as meaningful AI classifications.

## UX requirements

- Mobile-first, card-centric, visually quiet.
- Main / Grouped / Archived should be views over **one collection**, not separate data stores.
- Capture must be faster and less intrusive than sending a link to Telegram.
- Share receiver should not require confirmation, categorization, or account creation.
- Swipes must have clear visual feedback and an undo affordance where appropriate.
- Support empty, loading, failed-preview, and offline states.
- Maintain readable previews even when the image is missing.
- Respect Android system back navigation and accessibility basics.

## Explicitly out of scope

- Cross-device synchronization and cloud accounts
- Social feeds, following, sharing with other users
- Collaborative collections
- Manual folders and tagging
- AI-generated summaries or recommendations
- Full article extraction, offline copies, or media downloads
- Browser extensions and web apps
- Notification-driven engagement

## Suggested implementation sequence

1. Inspect the existing repository and follow its conventions; do not assume it is empty.
2. Create the smallest native Android app with Compose navigation among Main, Grouped, and Archived.
3. Implement Room storage and basic link lifecycle operations.
4. Implement the Android share receiver and direct URL paste flow; validate with real shares from Chrome, YouTube, and WhatsApp where possible.
5. Add background preview extraction and fallback cards.
6. Add swipe gestures, tap-to-open, and undo.
7. Add local search and deterministic grouping.
8. Add focused tests for URL extraction/normalization, deduplication, lifecycle transitions, search, and grouping.

## MVP acceptance criteria

- A valid URL shared from another Android app appears in Main without requiring extra form submission.
- Capture works offline; preview can be populated when network access returns.
- The app remains usable if preview extraction fails.
- Main sorts active links newest first.
- Grouped displays the same active links without duplication, using a documented similarity heuristic.
- Archived contains archived links, and they no longer appear in Main or Grouped.
- Swipe left removes an item from active feeds; swipe right archives it.
- Tapping opens the original URL but leaves the item's inbox/archive state unchanged.
- Search finds relevant saved items, including archived items when that scope is selected.
- Repeated shares of equivalent URLs follow the documented deduplication policy.
- No account, server, or external AI service is required.

## Decisions / assumptions for Codex to surface

- **Unseen semantics:** provisional interpretation is inbox/not archived, even if opened.
- **Grouping:** similarity-based is desired; deterministic heuristic is acceptable for MVP, but domain-only grouping may be too weak. Propose and document a practical baseline.
- **Deletion:** decide between soft delete with undo and permanent deletion after undo expiry.
- **Duplicate shares:** propose canonicalization rules that avoid incorrectly merging distinct URLs.
- **Preview extraction:** choose an approach that handles redirects, blocked sites, and unsafe URLs gracefully.

Do not block the initial vertical slice on these choices. State assumptions in the README and keep them easy to revise.

## Instruction to Codex

Implement this as a focused Android MVP. Start by inspecting the repository, summarize the proposed architecture and milestones briefly, then implement the smallest end-to-end share → persist → display → archive/delete → open flow. Favor executable code and tests over elaborate specifications. Avoid speculative features and unnecessary dependencies.
