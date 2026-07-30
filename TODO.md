# TODO

The live task list and journal for this project. Single source of truth for active and
completed work — status lives here only (see `CLAUDE.md` → documentation map).

Add proposed work **unchecked**; check off and **date** completed items; remove dropped
ones with a one-line why.

## Active

- [ ] Finish the remaining `[…]` placeholders in `CLAUDE.md` (pin the stack; fill the
      build/vet/test command loop once the Android project exists).
- [ ] Write `docs/architecture.md` — the load-bearing piece: the `.xopp` format mapping
      (gzip + XML schema → strokes, layers, pages, text, images, backgrounds) and the
      read/write round-trip design. This is the source of truth for format fidelity.
- [ ] Write `README.md` (setup, build & run).
- [ ] Flesh out `docs/tools.md` with the real Android build/emulator pipeline; delete the
      placeholder example entry.
- [ ] Decide/pin the stack (Kotlin + Android SDK, drawing surface, gzip/XML libraries) and
      scaffold the Android project.

## Done

- [x] 2026-07-30 — Scaffolded project from `base/` template: `CLAUDE.md`, `docs/tools.md`,
      `TODO.md`, git repo.
- [x] 2026-07-30 — Filled in "What this project is" in `CLAUDE.md`: stylus-first Android app
      that round-trips Xournal++ `.xopp` files with desktop Xournal++ on Linux.
