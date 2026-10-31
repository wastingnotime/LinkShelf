# my-feed

Native Android personal link inbox: share a link, save it locally, and revisit it
in a quiet visual feed. No account, backend, sync, or AI service is required.

Status: repository bootstrap and implementation planning; no APK exists yet.

- [Product requirements](docs/product-handoff.md)
- [Architecture, assumptions, and milestones](docs/implementation-plan.md)

Proposed stack: Kotlin, Jetpack Compose, Room/SQLite, and WorkManager.
Opening a link never archives it. "Unseen" means still in the inbox.
Deletion uses soft delete with undo. Grouping uses deterministic domain and
normalized title keywords. Duplicate shares refresh capture time and restore an
archived/deleted item to the inbox; URL identity rules preserve meaningful queries.
These initial assumptions are revisable and specified in the implementation plan.
