# Repository Guidelines

`my-feed` owns a native Android, local-first personal link inbox. Read README.md,
docs/product-handoff.md, and docs/implementation-plan.md before changing code.

The Android app and its local data semantics are owned here. No backend, login,
sync, web app, or shared platform package is part of the MVP. The planned
application surface is apps/mobile; implementation details are internal. No
stable library or network API is exported. Android share behavior is the user
integration boundary. Record cross-repository dependencies explicitly; do not
depend on sibling checkouts.

## Repository Role

- Own repository-specific product or domain truth.
- Keep shared WNT guidance in user-space capabilities and the WNT MCP surface.
- Keep durable repository decisions in Markdown.
- Expose stable contracts before implementation details.

## Non-Responsibilities

- Do not copy WNT toolkit policy into this repository.
- Do not treat `wnt-tools` as this repository's source of truth.
- Do not depend on repository-local WNT installs.
- Do not make shared WNT guidance the responsibility of this repository.

## Scope Boundaries

- Root `AGENTS.md` defines this repository's identity and visitor guidance.
- `docs/` and `references/` may hold durable repository knowledge.
- `contracts/` should hold public boundary semantics when the repo exports them.
- `work/` may hold active change artifacts if the repository uses them.
- Any WNT shared behavior should remain discoverable from user-space, not copied here.

## Working Rules

- Use `wnt capability show mcp-usage` when you need MCP guidance.
- Use `wnt capability show project-surfaces` when you need project-surface defaults.
- Use conventional commit prefixes for committed changes.
- Keep repository-specific rules local to this repo.

## Repository Binding

- This repository was created with `wnt create-repository`.
- WNT shared guidance lives outside the repository and is discovered from user-space.

## WNT Repository Defaults

Shared WNT guidance lives in user-space capabilities and the WNT MCP surface.

- Use `wnt capability show mcp-usage` for MCP guidance.
- Use `wnt capability show project-surfaces` for project-surface defaults.
- Use `wnt create-repository <name>` when creating a new repository.
- Keep WNT toolkit policy out of the repository-local source of truth.

<!-- wnt:wnt-toolkit-hints:start -->
## WNT Toolkit Hints

Shared WNT knowledge is not repository truth. Resolve current WNT guidance from
user or container space through WNT capabilities or the WNT MCP surface.

```bash
wnt capability list
wnt capability show <capability-name>
```

Start with these capabilities when the task is about WNT repository behavior:

```bash
wnt capability show ai-assisted-repository-baseline
wnt capability show project-surfaces
wnt capability show change-discipline
wnt capability show cross-repo-findings
wnt capability show issue-pr-linking
wnt capability show release-delivery-validation
```

This hint block is additive discovery guidance. It is not repository identity;
root strategic docs and the rest of `AGENTS.md` remain owned by the consuming
repository.
<!-- wnt:wnt-toolkit-hints:end -->
