# TODO

The live task list and journal for this project. Single source of truth for active and
completed work — status lives here only (see `CLAUDE.md` → documentation map).

Add proposed work **unchecked**; check off and **date** completed items; remove dropped
ones with a one-line why.

## Active

- [ ] Finish the remaining `[…]` placeholders in `CLAUDE.md` (pin the stack; fill the
      build/vet/test command loop once the Android project exists).
- [ ] Flesh out `docs/architecture.md` — the load-bearing piece: document the concrete
      `.xopp` XML schema (gzip + XML → strokes, layers, pages, text, images, backgrounds),
      define the in-memory document model, and pin the stylus/render approach. (Prior-art
      survey and skeleton are done; the format mapping is still `[…]`.)
- [ ] Write `README.md` (setup, build & run).
- [ ] Flesh out `docs/tools.md` with the real Android build/emulator pipeline; delete the
      placeholder example entry.
- [ ] Decide/pin the stack (Kotlin + Android SDK, drawing surface, gzip/XML libraries) and
      scaffold the Android project.
- [ ] Build the UI with Material Design (Material 3 / Material You) — use Material components
      and layout for all app chrome (app bar, menus, dialogs, tool palette).

## Done

- [x] 2026-07-30 — Scaffolded project from `base/` template: `CLAUDE.md`, `docs/tools.md`,
      `TODO.md`, git repo.
- [x] 2026-07-30 — Filled in "What this project is" in `CLAUDE.md`: stylus-first Android app
      that round-trips Xournal++ `.xopp` files with desktop Xournal++ on Linux.
- [x] 2026-07-30 — Prior-art survey (no maintained native `.xopp` editor exists) recorded in
      `docs/architecture.md`; cloned the archived Xournal++ Mobile to `reference/` (git-ignored)
      as a format reference.
