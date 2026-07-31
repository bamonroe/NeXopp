# Xopp — a stylus-first Xournal++ editor for Android

**Xopp** opens, edits, and saves [Xournal++](https://github.com/xournalpp/xournalpp) `.xopp`
files on Android. Draw and handwrite with a pen/stylus on a tablet or phone, then save back to
the **same `.xopp` format** so the file round-trips cleanly to and from desktop Xournal++ on
Linux. The guiding principle is **format fidelity and round-trip safety**: a file edited on
Android reopens correctly on the desktop, and vice versa.

- What the project is and how to work in it: [`CLAUDE.md`](CLAUDE.md).
- How it works internally (the `.xopp` schema, data path, model): [`docs/architecture.md`](docs/architecture.md).
- Build/emulator tooling: [`docs/tools.md`](docs/tools.md).
- What's next (active tasks): [`TODO.md`](TODO.md); what's already shipped: [`FINISHED.md`](FINISHED.md).

> Status: the `.xopp` read/write core and its tests are in place, and the Android editor is
> functional — pen/highlighter/eraser drawing with pressure, colour and width pickers, undo/redo,
> zoom, pan, a page navigator (add/remove/jump), on-device authoring of text/image/LaTeX elements,
> LaTeX math rendering, multi-page documents with layers and backgrounds, and PDF import and
> export. The controls live in a vertical rail down the left edge. See `TODO.md` for what's next.

## Requirements

Everything builds through the **shared Android toolchain in `/data/android`** (a baked
`android-builder:local` container), so the only host requirement is Docker. You do **not** need
a local JDK, Android SDK, or Gradle. To run the app on a virtual device you additionally need a
KVM-capable host for that directory's headless emulator (see [`docs/tools.md`](docs/tools.md)).

## Build & test

The one command you need:

```sh
scripts/build.sh
```

This runs the full check loop through the shared toolchain container — **unit tests + a debug
APK**. The SDK image is already baked; the first run only downloads Gradle and dependencies
into a per-project `.gradle-cache/`, so later runs are fast.

Common variants:

```sh
scripts/build.sh testDebugUnitTest     # JVM unit tests only (no device needed)
scripts/build.sh clean assembleDebug   # clean build of the debug APK
scripts/build.sh <any gradle tasks>    # arbitrary Gradle tasks in the container
```

Outputs:

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Test report: `app/build/reports/tests/testDebugUnitTest/index.html`

The unit tests cover the `.xopp` round-trip (the colour codec, every element type, XML escaping,
model reserialization, a gzip round-trip, the PDF-background on-disk shape, and a fixture-driven
`FormatDriftTest` asserting schema coverage) plus the pure `render/` geometry (page layout,
gridlines, page ops, eraser hit-testing, text layout, undo/redo history). If a real
desktop-generated `udiff.xopp` is present at the repo root, an extra test round-trips it end to end
(it self-skips when absent).

## Run on a device / emulator

Install the debug APK on a connected device or a running emulator:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.xopp.android/.MainActivity
```

Emulator setup (AVD creation, headless launch, KVM notes) lives in
[`docs/tools.md`](docs/tools.md).

## Using the app

- **Open** — the top-bar **menu** (the ☰ button, top right) has **Open**; it launches the system
  file picker; choose a `.xopp` file. It's read in place via the Storage Access Framework. Every
  page is shown, one above the next, each drawn with its own background ruling (plain, lined,
  ruled, graph, or dotted) and all of its layers — including strokes, text boxes, images, and
  LaTeX images (rendered as real math — fractions, super/subscripts, roots, and Greek/operator
  symbols; malformed formulae fall back to their source text).
- **Import PDF** — the menu's **Import PDF** launches the picker filtered to PDFs; choosing one
  builds a fresh document with **one page per PDF page**, each PDF page rasterised and shown as the
  page background (à la desktop Xournal++ PDF annotation). Draw on top as usual; the strokes are
  kept separate from the PDF and the `pdf` backgrounds round-trip when you **Save** the `.xopp`.
- **Draw** — the controls live in a **vertical rail down the left edge**. Its buttons — **Tool**,
  **Colour**, **Size**, **Zoom**, **Pages** — each open a small pop-up anchored to their own button
  (opening to the right of the rail). Pick **Pen** or **Highlighter** and draw with **one finger or
  the stylus**; pen pressure sets stroke width. Choose a **colour** (swatches) and a base **width**
  (S / M / L) from the other two pop-ups. New strokes land on the top layer of whichever page you
  draw on.
- **Erase** — pick **Eraser** from the Tool pop-up and drag over strokes to delete them; each
  stroke the eraser touches is removed whole. If your stylus has an **eraser tip** (the flip-over
  end), using it erases no matter which tool is selected; so does holding the stylus **barrel button**
  (configurable — see **Settings**).
- **Stylus** — the app is stylus-first. Rest your **palm** on the screen while you write: once the pen
  is down, finger/palm touches are ignored for drawing (a second finger still pans). A hovering stylus
  shows a **preview ring** where the tip will land. Pen **pressure** sets stroke width, with a
  configurable feel. All of this is tuned in **Settings** below.
- **Select** — pick **Select** from the Tool pop-up to select objects the way desktop Xournal++
  does. **Drag a box** (rubber-band) and every object fully inside it is selected; or **tap** a
  single object to select just that one. Selected objects get a dashed outline. **Drag inside the
  outline** to move them, and a floating **Delete / Deselect** bar appears at the bottom — Delete
  removes the selection, Deselect clears it. Move and delete are undoable. (Selection is per page;
  two-finger pan still works.)
- **Text** — pick **Text** from the Tool pop-up and **tap** where you want a text box; a dialog
  takes the content from the keyboard. Tapping an existing text box reopens it for editing (clearing
  the text deletes the box). New text uses the current pen colour.
- **Image** — pick **Image** and **tap** where the image should go; the system picker opens, and the
  chosen picture is placed at that point (scaled to a sensible size).
- **LaTeX** — pick **LaTeX** and **tap** to place a math image; type the LaTeX source (e.g.
  `\frac{a}{b}`, `x^2`, `\sqrt{y}`, `\alpha`) and it's rendered as real math.
- **Undo / Redo** — the arrows in the top bar undo and redo edits, one gesture at a time (drawing,
  erasing, and adding/editing text/image/LaTeX are all undoable). They enable and disable as history
  allows; opening a file starts fresh history.
- **Scroll** — drag with **two fingers** to move around the page stack, or pick the **Hand** tool
  from the Tool pop-up to pan with **one finger** (handy on a stylus).
- **Zoom** — the **%** button on the rail opens a zoom pop-up with **−** / **+** buttons;
  tap the percentage to reset to 100%. Zooming wider than the screen lets you pan sideways.
- **Pages** — the document button on the rail opens the **page navigator**: it shows **Page N / M**
  with **◀ / ▶** to jump to the previous/next page, plus **Add page** (a blank page inheriting the
  current page's size and background, after the one in view) and **Remove page** (the one in view;
  the last page is never removed). Add and remove are undoable.
- **Settings** — the top-bar menu opens a full-screen **Settings** page for the stylus (your choices
  persist across restarts):
  - **Finger draws** — on by default; turn it **off** so fingers only pan/zoom and can never leave ink
    (best on a stylus tablet where a palm would otherwise draw).
  - **Hover preview** — show a ring where a hovering stylus will land.
  - **Barrel button** — what the stylus side-button does while held: **Erase** (default), **Select**,
    or **None**.
  - **Pressure sensitivity** — **Soft** (thickens with a light touch), **Linear**, or **Firm** (needs
    a harder press).
- **Export PDF** — the menu's **Export PDF** flattens the whole document to a PDF: each page is
  drawn at its true size with its background (a PDF page or a ruled sheet) and every stroke and
  element merged on top, then written to the location you pick. Use this to share an annotated
  copy; **Save** keeps the editable `.xopp`.
- **Save** — the menu's **Save** writes the whole document back out as a `.xopp` file (gzip + XML),
  preserving every page, layer, background, and element — strokes plus the text, images, and LaTeX
  images you authored on-device.

The file on disk is the only source of truth — there's no cloud, account, or custom format.

## Project layout

The authoritative layout lives in [`docs/architecture.md`](docs/architecture.md). In short:

```
app/src/main/java/com/xopp/android/
  format/      # .xopp read/write: model, colour codec, gzip, dependency-free XML layer
  render/      # stylus canvas, page layout/rendering, PDF import & export
  ui/          # Compose Material 3 editor screen, left toolbar rail pop-ups, settings, theme
  MainActivity.kt
app/src/test/  # JVM unit tests for the format and render layers
Dockerfile, compose.yaml, scripts/build.sh   # containerized build
```
