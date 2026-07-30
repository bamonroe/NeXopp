# TODO

The live task list and journal for this project. Single source of truth for active and
completed work — status lives here only (see `CLAUDE.md` → documentation map).

Add proposed work **unchecked**; check off and **date** completed items; remove dropped
ones with a one-line why.

## Active

- [ ] Add a **format drift test** that round-trips a set of desktop-generated `.xopp` fixtures
      (not just `udiff.xopp`) and asserts semantic equality, keeping `docs/architecture.md`'s
      schema a faithful mirror of the code.
- [ ] Render page backgrounds (plain/lined/ruled/graph/dotted) and support **multi-page**
      documents and **layers** in the editor (the model already carries them; the
      `DrawingSurfaceView` currently draws page 1, one layer, strokes only).
- [ ] Implement the remaining element types in the editor: **text boxes**, **images**, and
      **teximage** (read + render + edit; the format layer already round-trips them).
- [ ] Implement the **eraser** tool behaviour (currently selectable but a no-op) and pen
      colour/width pickers in the Material 3 chrome.
- [ ] Add pan/zoom to the canvas and a page navigator.
- [ ] Script the **emulator harness** (AVD create + headless launch + install) per
      `docs/tools.md`; wire an instrumented smoke test.

## Done

- [x] 2026-07-30 — Switched builds to the shared `/data/android` toolchain (baked
      `android-builder:local`); verified end to end: `BUILD SUCCESSFUL`, `app-debug.apk` builds,
      and all 9 unit tests pass — including the real 3981-stroke `udiff.xopp` round-trip.
- [x] 2026-07-30 — Documented the concrete `.xopp` XML schema (elements/attributes, units,
      coordinate system, colour encoding, round-trip hazards) in `docs/architecture.md`, derived
      from `udiff.xopp` and the reference clone.
- [x] 2026-07-30 — Pinned the stack (Kotlin + Compose Material 3, custom `SurfaceView` for
      stylus, built-in gzip + dependency-free XML) and recorded the data path, in-memory model,
      and repository layout in `docs/architecture.md`.
- [x] 2026-07-30 — Scaffolded the single-module Gradle (Kotlin DSL) Android project with a
      containerized Docker/Podman build (`Dockerfile`, `compose.yaml`, `scripts/build.sh`,
      Gradle wrapper).
- [x] 2026-07-30 — Implemented the lossless `.xopp` read/write core (model, colour codec,
      pure-Kotlin XML reader/writer, gzip open/save) with JVM unit tests covering every element
      type, escaping, reserialization, gzip, and the real 3981-stroke sample.
- [x] 2026-07-30 — Built the Material 3 editor UI (top bar, tool palette, Material You theme)
      and the low-latency stylus `DrawingSurfaceView`; wired open/save via the Storage Access
      Framework in `MainActivity`.
- [x] 2026-07-30 — Filled the remaining `CLAUDE.md` placeholders (stack + build/check loop) and
      wrote `README.md` (setup, build & run) and the real `docs/tools.md` build/emulator pipeline.

- [x] 2026-07-30 — Scaffolded project from `base/` template: `CLAUDE.md`, `docs/tools.md`,
      `TODO.md`, git repo.
- [x] 2026-07-30 — Filled in "What this project is" in `CLAUDE.md`: stylus-first Android app
      that round-trips Xournal++ `.xopp` files with desktop Xournal++ on Linux.
- [x] 2026-07-30 — Prior-art survey (no maintained native `.xopp` editor exists) recorded in
      `docs/architecture.md`; cloned the archived Xournal++ Mobile to `reference/` (git-ignored)
      as a format reference.
