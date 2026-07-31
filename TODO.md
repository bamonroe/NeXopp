# TODO

The live task list and journal for this project. Single source of truth for active and
completed work — status lives here only (see `CLAUDE.md` → documentation map).

Add proposed work **unchecked**; check off and **date** completed items; remove dropped
ones with a one-line why.

## Active

- [ ] **Edit** the non-stroke element types on-device: create/edit **text boxes** (keyboard),
      insert **images** (via SAF), and place **teximage**. Rendering already works; this is the
      authoring path.
- [ ] Render **teximage** as real math (a LaTeX renderer), replacing the source-text placeholder.
- [ ] Add pan/zoom to the canvas and a page navigator.
- [ ] Script the **emulator harness** (AVD create + headless launch + install) per
      `docs/tools.md`; wire an instrumented smoke test.

## Done

- [x] 2026-07-30 — **Undo / redo.** Top-bar arrows undo and redo edits one draw/erase gesture at a
      time, enabling/disabling as history allows; opening a file resets history. Each gesture
      snapshots the whole document into the pure, tested generic `EditHistory` (6 new JVM tests, 38
      total). Verified on the `/data/android` emulator: drew strokes, undid to empty (Undo greyed),
      redid to restore, no crash. Installed to both tailnet devices.
- [x] 2026-07-30 — **Pen colour and width pickers.** Added `PenSettings` to the Material 3 chrome
      — a scrollable row of colour swatches (black/red/blue/green/orange/yellow) and S/M/L width
      chips — wired to the surface's `colorArgb`/`baseWidthPt`. Verified on the `/data/android`
      emulator: picked red + L and drew a red, thicker stroke; installed to both tailnet devices.
- [x] 2026-07-30 — **Eraser tool (delete-stroke).** Selecting Eraser and dragging now deletes any
      stroke the eraser disc touches on the page under the finger; hit geometry (point-to-segment
      distance, accounting for pen half-width) lives in the pure, tested `StrokeHitTester`. 6 new
      JVM tests (32 total), `BUILD SUCCESSFUL`. Verified on the `/data/android` emulator: drew a
      pen stroke, switched to Eraser, dragged over it, stroke gone, no crash.
- [x] 2026-07-30 — **Render text boxes, images, and teximage in the editor.** New `ElementRenderer`
      draws `<text>` (via the pure, tested `TextBlock` line/baseline geometry), `<image>` (bytes
      decoded once and cached by element identity), and `<teximage>` (best-effort placeholder box
      with its LaTeX source). Wired into `DrawingSurfaceView` so every element in every layer
      paints. 4 new JVM tests (26 total), `BUILD SUCCESSFUL`. Verified on the `/data/android`
      emulator with `text-image.xopp`: text with decoded entities, the PNG image, and the teximage
      placeholder all render, no crash. Also installed the debug APK on the two tailnet devices
      (Pixel 8a, Tab S9 Ultra). Left: on-device authoring of these elements (next item).
- [x] 2026-07-30 — **Multi-page + backgrounds + layers in the editor.** `DrawingSurfaceView` now
      holds the whole document and renders every page stacked top-to-bottom (`PageStacker`), each
      with its background ruling — plain/lined/ruled/graph/dotted (`BackgroundRenderer` +
      `BackgroundGrid`) — and all layers in z-order; one finger draws, two fingers scroll. Save
      no longer flattens to one page/layer — it preserves all pages, layers, and unmodelled
      elements. 7 new JVM geometry tests (22 total). Verified on the `/data/android` emulator:
      graph grid + pen drawing, and a loaded multi-page fixture showing plain then lined pages
      with their strokes. Caught and fixed a stale-layout bug (appended strokes weren't
      re-rendering) via on-device testing. Left: two-finger scroll gesture not yet auto-tested.
- [x] 2026-07-30 — Added a **format drift test** (`FormatDriftTest`) over a committed set of
      desktop-format `.xopp` fixtures (`app/src/test/resources/fixtures/`, each validated to
      load in desktop Xournal++ 1.3.5): asserts model round-trip equality and that the set
      covers every background style, multi-page, layers, all element types, pressure vs.
      uniform width, and highlighter alpha. 15 unit tests pass (6 new), `BUILD SUCCESSFUL`.
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
