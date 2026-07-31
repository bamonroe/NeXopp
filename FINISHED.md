# Finished

The archive of **completed** work for this project — the graveyard for done tasks so
`TODO.md` stays a short list of *active* work only (see `CLAUDE.md` → documentation map).

When a task is fully finished (built, tested, documented), move its checked, dated entry
here from `TODO.md`. Newest first. This file only grows; nothing is removed from it.

- [x] 2026-07-31 — **Selection tool to desktop parity.** Extended the object-selection tool from
      rectangle-select + move + delete to the full desktop feature set, all as pure, JVM-tested
      `SelectionOps`/`SelectionTester` ops so the logic stays off the Android surface: (1) **resize**
      via four corner handles (uniform scale about the opposite corner, `SelectionOps.scale`, backed
      by a single `affine` primitive); (2) **rotate** via a top knob (`SelectionOps.rotate`) — *stroke
      only by the scope rule*, since a stroke bakes rotation into its vertex coordinates and
      round-trips but text/images have no rotation attribute in `.xopp`, so the knob is shown only for
      an all-stroke selection; (3) **lasso** (free-form) select alongside the rectangle
      (`SelectionTester.inPolygon`, wholly-enclosed semantics); (4) **cut / copy / paste / duplicate**
      through a view-held element clipboard (`elementsAt` + `addToTopLayer`, which reports the pasted
      refs so copies land selected; paste targets the visible page); (5) **move across pages** —
      dropping a move over another page re-homes the elements through both pages' pt frames
      (`SelectionOps.moveToPage`); and (6) **recolour / re-width** the selection (`SelectionOps.restyle`).
      New UI: a scrollable selection action bar (cut/copy/duplicate/palette/line-weight/delete/done)
      and a select-mode bar (Rectangle/Lasso chips + Paste). Tilt/orientation was dropped from the
      roadmap and a **"the format is the boundary" scope rule** recorded (`CLAUDE.md`) — we only build
      features `.xopp` can represent. Extended `SelectionTest` (resize/rotate/restyle/clipboard/
      moveToPage/lasso) plus a new instrumented `SelectionInputTest` (synthetic finger gestures for
      lasso, copy/paste, cross-page move). `BUILD SUCCESSFUL`; all JVM unit tests + all 9 instrumented
      tests pass on the `/data/android` emulator, where select → resize/rotate handles, lasso,
      copy/paste and recolour were also confirmed live via screenshots with a clean logcat. Installed
      to the Tab S9 Ultra; the Pixel 8a was offline (install deferred).
- [x] 2026-07-31 — **Stylus-first input layer.** Routed `DrawingSurfaceView.onTouchEvent` through a
      new pure, JVM-tested **`InputClassifier`** (`PointerKind` + barrel state + `ActiveTool` +
      `InputSettings` → `GestureIntent`), so pen hardware wins over the toolbar the way desktop
      Xournal++ does: (1) the flipped-over **eraser tip** (`TOOL_TYPE_ERASER`) erases whatever the
      tool; (2) the **barrel button** (`BUTTON_STYLUS_PRIMARY`) does a configurable action while held
      (default erase, or select); (3) **palm rejection** — the gesture is owned by a pointer id and
      only that pointer is sampled, a stylus takes over a gesture a resting finger/palm started, and
      once a stylus owns the stroke extra finger/palm pointers are ignored; (4) a Settings **"finger
      draws"** toggle makes fingers pan-only for non-stylus-safe writing; (5) a configurable
      **pressure curve** (`PressureCurve`, Soft/Linear/Firm — Linear reproduces the old
      `0.4+0.6·pressure` exactly) replacing the hard-coded response; and (6) a **hover** preview ring
      from `ACTION_HOVER_MOVE`. New pure pieces `InputClassifier` + `PressureCurve` with JVM tests
      (`InputClassifierTest`, `PressureCurveTest`), a real **Settings** screen (`AppSettings` +
      `SettingsStore` SharedPreferences persistence, wired through `EditorScreen`/`MainActivity`), and
      on-device `StylusInputTest` (eraser tip, barrel erase, finger-draw gate, palm rejection — driven
      with synthetic tool-typed `MotionEvent`s). `BUILD SUCCESSFUL`; all JVM unit tests + all 6
      instrumented tests pass on the `/data/android` emulator. Verified on-device: Settings screen
      renders and its toggles persist across a force-stop/relaunch, finger drawing still works
      (no regression from the touch rewrite), and the finger-draw toggle changes behaviour. Only
      tilt-driven width remains (see TODO Active — no format home). Installed to the Galaxy Tab S9 Ultra
      (SM-X920, S-Pen — the ideal target); the Pixel 8a was offline on the tailnet at install time
      (catch it up on the next build).
- [x] 2026-07-31 — **Fixed: `.xopp` saved with a `.gz` suffix.** The Save intent used the
      `application/gzip` MIME, so the Storage Access Framework appended its own `.gz` extension
      (`document.xopp.gz`), which desktop Xournal++ won't open by name. Switched the save MIME to
      `application/octet-stream` (which the framework has no canonical extension for), so SAF keeps the
      exact `document.xopp` name; the bytes are gzip either way (`Xopp.save` always gzips). Verified
      on the emulator by driving the real Save dialog: the created file is exactly
      `/sdcard/Download/document.xopp` (no `.gz`) with gzip magic `1f 8b`.

- [x] 2026-07-31 — **Selection tool (rectangle + tap select, move, delete).** New **Select** tool on
      the rail (`EditorTool.SELECT` → the surface's `selectMode`) matching desktop Xournal++: a
      one-finger drag rubber-bands and selects every object wholly enclosed; a tap picks the topmost
      object; dragging inside the dashed outline moves the selection (live, undoable); a floating
      **Delete / Deselect** bar (`SelectionActionBar`) removes or clears it. Selection is per-page and
      addresses elements by position (`ElementRef`), stable across a drag. New pure, JVM-tested pieces:
      `ElementBounds` (+ `Bounds`), `SelectionTester` (rect/tap/union), `SelectionOps` (translate/
      delete) — 2 new test files, `BUILD SUCCESSFUL`. Documented the mechanics and a **stylus &
      selection roadmap** in `docs/architecture.md` (+ README "Select"). Verified on the
      `/data/android` emulator: drew two strokes, band-selected one (outline wraps it, the other
      untouched, Delete/Deselect bar appears), dragged it to a new spot, Deleted it (gone), Undo
      restored it at the moved position, and tap-selected a single stroke. Caught and fixed a bug
      on-device where the rubber-band marquee persisted after release (`commitBand` rendered before
      the `banding` flag was cleared). Installed to the Galaxy Tab S9 Ultra; the Pixel 8a was offline
      on the tailnet at install time (catch it up on the next build).

- [x] 2026-07-31 — **Moved the toolbar to a left vertical rail.** `BottomToolbar.kt` →
      `SideToolbar.kt`: the chrome is now a scrollable `Column` rail down the left edge (Tool /
      Colour / Size / Zoom / Pages), and `EditorScreen` lays out `Row[rail, canvas]` instead of
      `Column[canvas, bar]`. Verified on the `/data/android` emulator: rail sits on the left, the
      Tool pop-up lists all seven tools, canvas fills the rest. Installed to both tailnet devices.
- [x] 2026-07-31 — **On-device authoring of text / image / teximage.** Selecting Text, Image, or
      LaTeX arms a placement: a canvas tap raises `onPlace` with the page-space point. Text and
      LaTeX open a keyboard dialog (text is also **editable** — tapping an existing box re-opens it,
      blank deletes it); Image launches the SAF picker (`image/*`) and inserts the decoded bytes
      scaled to a default box. All are undoable. Verified on the `/data/android` emulator: placed a
      text box ("HELLO123"), a LaTeX formula, and a picked PNG — all render and coexist with pen
      strokes; Undo removed the formula, Redo restored it. Installed to both tailnet devices.
- [x] 2026-07-31 — **Render teximage as real math (LaTeX renderer).** New dependency-free
      `LatexParser` (pure, `LatexNode` tree — rows, sub/superscripts, `\frac`, `\sqrt`, grouping,
      Greek/operator Unicode; never throws) + `LatexRenderer` (measures then scales to fit the box,
      draws fraction bars / scripts / roots), replacing the source-text placeholder in
      `ElementRenderer.drawTex` (parse cached by element identity, fallback to monospace source on
      error). 12 new JVM `LatexParserTest` cases. Verified on the `/data/android` emulator:
      `\frac{a}{b}+x^2` renders as a real fraction with a superscript. Installed to both tailnet devices.
- [x] 2026-07-31 — **Page navigator.** The rail's Pages pop-up shows `Page N / M` with ◀/▶ that
      jump the scroll to the previous/next page (disabled at the ends), alongside the existing
      Add/Remove page. `DrawingSurfaceView` gained `goToPage` and reports the current page as the
      view scrolls. Verified on the `/data/android` emulator: added a page (`1/1`→`1/2`), Next
      scrolled to page 2 (`2/2`, ▶ greyed), Remove returned to `1/1`. Installed to both tailnet devices.
- [x] 2026-07-31 — **Instrumented smoke test.** `app/src/androidTest/.../SmokeTest.kt` (AndroidJUnit4)
      round-trips a document through `Xopp.save`/`Xopp.open` on-device, and drives a real
      `DrawingSurfaceView` with synthetic `MotionEvent`s (down → moves → up) then asserts the new
      stroke survives save/reopen. Runs via `scripts/build.sh connectedDebugAndroidTest`
      (androidx.test runner/rules/espresso added). Both tests pass on the `/data/android`
      emulator (`am instrument` — Gradle's own adb can't see the emulator container).

- [x] 2026-07-30 — **Export the annotated document as a flattened PDF.** The menu's **Export PDF**
      writes each page at its true point size via the framework `PdfDocument` (dependency-free): the
      background (a rasterised `pdf` page, or a solid sheet with its ruling) then every stroke and
      element on top, reusing `BackgroundRenderer`/`PageRenderer` at scale 1 so the output matches
      the editor. Extracted the shared `StrokePainter`/`PageRenderer` out of `DrawingSurfaceView` so
      screen and export draw identically. New JVM `PdfBackgroundRoundTripTest` (48 tests total),
      `BUILD SUCCESSFUL`. Verified on the `/data/android` emulator: imported a 2-page PDF, drew an X
      on page 1, exported, and confirmed the output PDF (via `pdftoppm`) shows the original PDF text
      plus the X on page 1 and clean page 2.
- [x] 2026-07-30 — **Import a PDF as an annotatable document (PDF page backgrounds).** The menu's
      **Import PDF** copies the picked PDF into app cache and builds a document with one page per PDF
      page; each page carries a `<background type="pdf">` (filename+domain on page 1 only, `pageno`
      thereafter — the desktop convention). Pages are rasterised on demand by the framework
      `PdfRenderer` (dependency-free) in the new `PdfPageCache` (serialised, bounded bitmap cache),
      and `BackgroundRenderer` draws the page image; a `.xopp` whose PDF isn't present falls back to
      a plain sheet. `BUILD SUCCESSFUL`, 40 tests pass. Verified on the `/data/android` emulator:
      imported a 2-page PDF (both pages rendered with their text/border), drew an X over page 1
      (Undo lit), no crash. Export to PDF is the remaining half — see Active.
- [x] 2026-07-30 — **Emulator harness already scripted.** The AVD-create / headless-launch /
      install loop lives in `/data/android/.claude/skills/android-dev/scripts/emulator.sh`
      (`status`/`up`/`boot-wait`/`down`/`install`/`launch`/`screenshot`/`ui`/`logcat`/`shell`/`adb`),
      driven by that repo's
      `docker-compose.yml`; the pre-baked Android 14 container image *is* the AVD, so there's no
      separate create step. Documented in `docs/tools.md`. (An instrumented smoke test on top of
      this is still worth adding — see Active.)

- [x] 2026-07-30 — **Fixed: drawing wiped when visiting Settings.** Opening Settings early-`return`ed
      from `EditorScreen`, tearing the `AndroidView`-hosted `DrawingSurfaceView` out of the
      composition; since strokes lived only in that view, coming back rebuilt a blank one. Settings
      now renders as an opaque overlay in a `Box` on top of the still-composed editor, so the surface
      is never detached — the drawing **and** undo history survive the round trip. `BUILD SUCCESSFUL`,
      40 tests pass. Verified on the `/data/android` emulator: drew two strokes, entered Settings
      (canvas fully covered, no bleed-through), backed out — both strokes and the lit Undo button
      remained, no crash. Installed to both tailnet devices.

- [x] 2026-07-30 — **Hand tool, zoom, and add/remove page.** The Tool pop-up gained a **Hand**
      mode (one-finger pan; two-finger pan works in every tool). A **Zoom** button opens a −/+/reset
      pop-up; zoom multiplies the fit-to-width scale in `PageStacker`, pages wider than the view pan
      horizontally, narrower pages centre (`PageBox.leftPx`). A **Pages** button adds a blank page
      (inheriting the current page's size/background, via the pure `PageOps`) or removes the page in
      view (never the last one), showing the page count; both are undoable. New pure tests for
      `PageOps` and `PageStacker` zoom/centering. `BUILD SUCCESSFUL`, all tests pass. Verified on the
      `/data/android` emulator: added a page (count 1→2, page break visible, Undo lit), zoomed to
      156% (grid enlarged, live label), Hand-panned to the page break, removed a page (2→1), no
      crash. Installed to both tailnet devices.

- [x] 2026-07-30 — **Rearranged the chrome into pop-up groups + an overflow menu.** The bottom bar
      is now three buttons — Tool, Colour, Size — each opening a small `DropdownMenu` **anchored to
      its own button** (not full-screen, not centred); `ToolPalette`/`PenSettings` were folded into
      one `BottomToolbar.kt`. The top bar keeps undo/redo and gains a **☰ overflow menu** with Open,
      Save, and a new full-screen **`SettingsScreen`** (placeholder). `BUILD SUCCESSFUL`, 38 tests
      pass. Verified on the `/data/android` emulator: opened each of the three bottom pop-ups (Tool
      checked, Colour swatch row, Size S/M/L), picked red, opened the ☰ menu, entered Settings and
      backed out — no crash. Installed to both tailnet devices.
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
