# Xopp — a stylus-first Xournal++ editor for Android

**Xopp** opens, edits, and saves [Xournal++](https://github.com/xournalpp/xournalpp) `.xopp`
files on Android. Draw and handwrite with a pen/stylus on a tablet or phone, then save back to
the **same `.xopp` format** so the file round-trips cleanly to and from desktop Xournal++ on
Linux. The guiding principle is **format fidelity and round-trip safety**: a file edited on
Android reopens correctly on the desktop, and vice versa.

- What the project is and how to work in it: [`CLAUDE.md`](CLAUDE.md).
- How it works internally (the `.xopp` schema, data path, model): [`docs/architecture.md`](docs/architecture.md).
- Build/emulator tooling: [`docs/tools.md`](docs/tools.md).
- What's next (active tasks): [`TODO.toml`](TODO.toml); what's already shipped: [`FINISHED.toml`](FINISHED.toml). These are TOML task files driven by the `todo` skill — run `scripts/todo.sh list` or `scripts/todo.sh stats` to read them.

> Status: the `.xopp` read/write core and its tests are in place, and the Android editor is
> functional — pen/highlighter/eraser drawing with pressure, colour and width pickers, undo/redo,
> zoom, pan, a page navigator (add/remove/jump), on-device authoring of text/image/LaTeX elements,
> LaTeX math rendering, multi-page documents with layers and backgrounds, and PDF import and
> export. The controls live in a vertical rail down the left edge. Run `scripts/todo.sh list` for what's next.

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
`FormatDriftTest` asserting schema coverage, and an `XmlEqualityRoundTripTest` that checks the
XML we emit still matches the desktop-written source byte-for-byte once normalized) plus the pure `render/` geometry (page layout,
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
  symbols; malformed formulae fall back to their source text). If the `.xopp` was made by
  annotating a PDF, its **PDF background is reloaded automatically** — the reference the file
  stores is resolved and the PDF pages render underneath your annotations again, so a saved
  project reopens intact. If the referenced PDF can't be found (e.g. a desktop path that doesn't
  exist on the device), those pages open blank and a notice is shown.
- **Import PDF** — the menu's **Import PDF** launches the picker filtered to PDFs; choosing one
  builds a fresh document with **one page per PDF page**, each PDF page rasterised and shown as the
  page background (à la desktop Xournal++ PDF annotation). Draw on top as usual; the strokes are
  kept separate from the PDF and the `pdf` backgrounds round-trip when you **Save** the `.xopp`.
  The source PDF's reference is recorded in the saved file (as an absolute reference to the picked
  PDF), so reopening the `.xopp` later **reloads that PDF** and shows the same backgrounds again —
  no need to re-import.
- **Select text (PDF)** — for an imported PDF that carries a real text layer (i.e. not a pure scan),
  the Tool pop-up's **Select text (PDF)** tool lets you **drag across the page to select the
  underlying text**; the selected words highlight, and a **Copy** button puts them on the system
  clipboard to paste elsewhere. It reads the PDF's own text — no OCR — so scanned image-only PDFs
  have nothing to select (OCR for those is planned). The selection is view-only and doesn't change
  the document.
- **Vertical space** — pick **Vertical space** from the rail to reflow a page: **drag down** on the
  page to insert blank vertical space, pushing everything below the grab line down with your finger,
  or **drag up** to close a gap and pull that content back. A dashed guide shows the grab line while
  you drag; only objects whose **top** edge is below the line move (one the line passes through stays
  put rather than being torn), and **all layers move together**. The whole drag is a single undo step,
  and since it only shifts coordinates it round-trips to desktop Xournal++ unchanged.
- **Draw** — the controls live in a **vertical rail down the left edge**. It starts with eight
  **tool slots**, each standing for a group of related tools and showing the one that group is
  currently set to: **Draw** (pen · highlighter), **Eraser**, **Line** (line · arrow), **Shape**
  (rectangle · ellipse), **Pan**, **Select** (select · select text), **Insert** (text · LaTeX ·
  image), and **Vertical space**. **Tap** a slot to switch to the tool it shows — the active slot is highlighted — or
  **long-press** it to pick a different member, which both switches to that tool and re-faces the
  slot. Those per-slot choices are **remembered across app restarts**, so the rail comes back the
  way you left it. The remaining buttons — **Colour**, **Size**, **Style**, **Layers**, **Zoom**,
  **Background**, **Pages** — each open a small pop-up anchored
  to their own button (opening to the right of the rail). Pick **Pen** or **Highlighter** and draw with
  **one finger or the stylus**; pen pressure sets stroke width. The **Highlighter** instead lays down a **broad,
  constant-width translucent band** (pressure-independent, ~6× the pen width) that shows the page
  through it — it saves as a `highlighter` stroke and reopens the same way in desktop Xournal++.
  Choose a **colour** (swatches) and a base **width**
  from the other two pop-ups. The Colour pop-up ends with an editable **custom slot** (marked with a
  pencil): **tap** it to draw with its current colour, or **long-press** it to open a picker —
  a saturation/value square over a hue slider plus a `#RRGGBB` hex field — to set any colour. The Size
  pop-up offers three width **slots** (**S / M / L**): **tap** a slot to draw with it, or
  **long-press** a slot to open a resize dialog (0.5 → 15 pt) that redefines that slot's width — drag
  the **slider** for a broad sweep, tap **−** / **+** to nudge it 0.1 pt at a time, or type an exact
  point size into the **text field**. Below the swatches the Colour pop-up shows a **Recent** row —
  the last seven colours you picked, most-recent-first — so a colour mixed in the custom picker stays
  one tap away after you move on. The custom colour, the three widths, the recent row, and the
  colour/width you were last drawing with are all remembered across restarts, so the app reopens with
  the pen you left off with. New strokes land on the **active layer**
  (see **Layers** below) of whichever page you draw on.
- **Shapes** — the Tool pop-up also offers **Line**, **Arrow**, **Rectangle**, and **Ellipse**. Pick
  one and **drag** from one corner/endpoint to the other; a live preview follows your finger and the
  shape commits on release. Shapes are saved as ordinary strokes in the current pen colour and width,
  so they round-trip to desktop Xournal++ like any other stroke.
- **Line style & fill** — the **Style** pop-up sets the pattern for strokes and shapes you draw next:
  **Solid**, **Dashed**, **Dash-dot**, or **Dotted**; and a **Fill** level (None / Light / Medium /
  Heavy / Solid) that floods the inside of a closed stroke or shape. Both save on the `<stroke>`
  element (`style` / `fill`) and reopen the same way in desktop Xournal++.
- **Erase** — pick **Eraser** from the Tool pop-up and drag over strokes. The **Style** pop-up's
  **Eraser** setting picks the mode: **Standard (partial)** rubs out just the part of a stroke the
  eraser passes over, splitting it into the surviving pieces; **Delete whole stroke** removes any
  stroke the eraser touches entirely. The same pop-up's **Eraser size** picks the tip —
  **Fine**, **Medium** or **Thick** — measured in document points, so it rubs out the same amount of
  ink whatever the zoom. Hidden layers are never erased. If your stylus has an **eraser tip** (the flip-over
  end), using it erases no matter which tool is selected; so does holding the stylus **barrel button**
  (configurable — see **Settings**).
- **Layers** — the **Layers** pop-up manages the visible page's layers (top of the list = top of the
  page). Each row can **make the layer active** (tap its name — new ink lands there, marked with a
  filled dot), **show/hide** it in the editor (the eye toggle — hiding is view-only and never changes
  the file), **reorder** it up/down (z-order), **rename** it, or **delete** it (a page always keeps at
  least one layer). **Add layer** puts a fresh empty layer on top. With something selected, each row
  also shows a **move-selection-here** button. Layer names round-trip via the `<layer name>` attribute;
  every structural change is undoable.
- **Stylus** — the app is stylus-first. Rest your **palm** on the screen while you write: once the pen
  is down, finger/palm touches are ignored for drawing (a second finger still pans). A hovering stylus
  shows a **preview ring** where the tip will land. Pen **pressure** sets stroke width, with a
  configurable feel, tapering more deeply at a light touch to match desktop Xournal++. Handwriting is
  **smoothed** as you write — digitiser wobble in both position and pressure is filtered out, and
  redundant points are dropped when the pen lifts, so strokes look clean and files stay small.
  All of this is tuned in **Settings** below.
- **Select** — pick **Select** from the Tool pop-up to select objects the way desktop Xournal++
  does. Choose the marquee shape from the bottom bar: **Rectangle** (drag a box; every object fully
  inside is selected) or **Lasso** (trace a free-form loop; everything wholly inside is selected).
  Or **tap** a single object to select just that one. Selected objects get a dashed outline with
  handles:
  - **Drag inside the outline** to move them — drag onto a **different page** to move them there.
  - **Drag a corner handle** to resize (uniform scale).
  - **Drag the round knob poking out from the right edge** to rotate — shown only when the selection is *all
    strokes* (text and images have no rotation in the `.xopp` format, so they can't be rotated).
  - The floating action bar offers **Cut**, **Copy**, **Duplicate**, a **palette** to recolour and
    a **line-weight** menu to re-width the selection, **Delete**, and **Done** (deselect).
  - **Paste** appears in the bottom bar (when nothing is selected) and drops the copied objects onto
    the page you're viewing.

  All of these are undoable. (Selection is per page; two-finger pan still works.)
- **Text** — pick **Text** from the Tool pop-up and **tap** where you want a text box; a dialog
  takes the content from the keyboard and lets you style it: **font family** (Sans / Serif /
  Monospace), **bold**, **italic**, a **size** slider (6–96 pt), and its own **colour**. Tapping
  an existing text box reopens it for editing with all of that prefilled from the box (clearing the
  text deletes it). Every property round-trips to and from desktop Xournal++ via the `.xopp`
  `<text>` element. (Underline isn't offered — the format can't store it.)
- **Image** — pick **Image** and **tap** where the image should go; the system picker opens, and the
  chosen picture is placed at that point (scaled to a sensible size).
- **LaTeX** — pick **LaTeX** and **tap** to place a math image; type the LaTeX source (e.g.
  `\frac{a}{b}`, `x^2`, `\sqrt{y}`, `\alpha`) and it's rendered as real math.
- **Undo / Redo** — the arrows in the top bar undo and redo edits, one gesture at a time (drawing,
  erasing, and adding/editing text/image/LaTeX are all undoable). They enable and disable as history
  allows; opening a file starts fresh history. History is **200 edits deep** — past that the oldest
  step is dropped, so the most recent edits always stay undoable without the stack growing forever.
- **Scroll** — drag with **two fingers** to move around the page stack, or pick the **Hand** tool
  from the Tool pop-up to pan with **one finger** (handy on a stylus). A quick **one-finger flick**
  keeps the pages **gliding** with momentum and coasts to a stop — the faster the flick, the much
  farther it carries — while a **two-finger** pan stops the instant you lift. Touch down again to halt
  a glide at once. With the
  Hand tool, a **double-tap** navigates: tap twice on the **left edge** to jump to the previous page,
  on the **right edge** for the next page, or in the **centre** to toggle **full-page view** (hides
  the top bar and side toolbar for a distraction-free canvas; double-tap the centre again to restore
  them). A
  PDF-style **scroll thumb**
  rides the **right edge** whenever the document is taller than the screen: **drag it** to page
  quickly through a long document (a **page-number bubble** shows where you are as you drag). A small
  **grip** bulges out of its centre so it's easy to grab. It sits faint while idle and brightens as you
  scroll; only the thumb itself grabs touches, so the rest of the page's right margin still takes ink.
- **Zoom** — **pinch** with **two fingers** anywhere on the canvas to zoom in or out; the point
  between your fingers stays put as the page grows or shrinks, and you can pan at the same time in
  the one gesture. The **%** button on the rail also opens a zoom pop-up with **−** / **+** buttons;
  tap the percentage to reset to 100%. Zooming wider than the screen lets you pan sideways. Zoom
  ranges from **25% to 1000%**, so you can work on fine detail; ink stays sharp at every level.
  PDF page backgrounds stay sharp too: past a certain zoom only the part of the page you can
  actually see is re-rendered, at full screen resolution, so PDF text is crisp all the way to 1000%.
  A freshly zoomed or panned area may look soft for a moment before the sharp version lands.
- **Background** — the **grid** button on the rail opens the **page-background** pop-up, which sets the
  paper ruling of the page in view: **Plain** (bare sheet), **Lined**, **Ruled** (lined with a red
  margin), **Graph**, or **Dotted**. The current style is check-marked; picking another re-rules the
  page immediately (an undoable edit) and round-trips via the `<background style>` attribute. On a
  **PDF** or image-backed page there's no solid sheet to re-rule, so the items are disabled.
- **Pages** — the document button on the rail opens the **page navigator**: it shows **Page N / M**
  with **◀ / ▶** to jump to the previous/next page, plus **Add page** (a blank page after the one in
  view — it keeps the current page's size and paper ruling, but a **PDF/image background is dropped to
  a plain white sheet** so the new page is genuinely blank, not a copy of the page underneath) and
  **Remove page** (the one in view; the last page is never removed). Add and remove are undoable.
  - **Page size…** — the last row shows the page-in-view's size (a preset name like **A4**, or its
    dimensions) and opens a **Page size** dialog: pick a preset (**A4 / A5 / Letter / Legal**), or type
    a **custom** width and height in **mm / in / pt** (the unit toggle converts the fields), and **swap**
    width↔height for landscape. **Set** resizes that page (undoable); the dimensions round-trip via the
    `<page width= height=>` attributes to desktop Xournal++.
- **Settings** — the top-bar menu opens **Settings**, a list of sections — **Stylus**, **Editor** and
  **Navigation**. Tap a section to open it as its own page; back returns to the list, and back from
  the list returns to the editor. Your choices persist across restarts. Under **Stylus**:
  - **Finger draws** — on by default; turn it **off** so fingers only pan/zoom and can never leave ink
    (best on a stylus tablet where a palm would otherwise draw).
  - **Hover preview** — show a ring where a hovering stylus will land.
  - **Barrel button** — what the stylus side-button does while held: **Erase** (default), **Select**,
    or **None**.
  - **Pressure sensitivity** — **Soft** (thickens with a light touch), **Linear**, or **Firm** (needs
    a harder press).
  - **Stroke precision** — how much of the pen's detail a stroke keeps: **Economy**, **Balanced**
    (default), **High**, or **Maximum**. Strokes are thinned to a sub-pixel error budget as they're
    drawn; raising the precision shrinks that budget, which draws visibly rounder curves on a large,
    high-density tablet at 100% zoom and below, at the cost of a bigger `.xopp`. Lowering it keeps
    files small. Existing strokes are unaffected — the setting applies to what you draw next.

  Under **Editor**:
  - **Default tool** — which tool is active when a document opens: **Pen** (default), **Highlighter**,
    **Eraser**, or **Hand (pan)**.

  Under **Toolbar**:
  - **Toolbar position** — which edge the tool rail is docked to: **Left** (default), **Right**,
    **Top**, or **Bottom**. Top/bottom lay the tool, colour, size, zoom, and page buttons out in a
    horizontal row along that edge; left/right keep the familiar vertical rail.
  - **Rail buttons** — the full list of rail positions (the seven tool slots plus Colour, Size,
    Style, Layers, Zoom, Background and Pages), each with a **switch** to hide it and **▲/▼** arrows
    to move it. The rail draws them in this order, top-to-bottom (left-to-right when docked
    horizontally). Both the order and the hidden set are **remembered across app restarts**.

  Under **Navigation**:
  - **Momentum scrolling** — a slider setting how far a **one-finger** pan keeps gliding after you
    flick it. **0** turns momentum off (a released pan stops dead), **1.0** is normal (the default —
    a moderate flick coasts at about the speed you flicked), and higher values up to **10.0×** stretch
    every coast farther. Two-finger pans never glide, whatever this is set to.
  - **Momentum curve** — picks how sharply a *faster* flick coasts *farther*: **Linear** (even),
    **Quadratic** (the default — coast grows with the square of flick speed), **Cubic**, or
    **Exponential** (rewards fast swipes the most, so a tiny flick barely drifts while a hard swipe
    flies many pages). All four meet at the same moderate-flick reference, so this only changes how
    small flicks fall off and fast ones take off — the slider above still sets the overall strength.
  - **Panning sensitivity** — a slider setting how far the canvas moves per unit of pan travel. **1.0**
    is one-to-one (the default — the page tracks your finger exactly), values **below 1** pan slower
    than your finger, values up to **4.0×** pan faster, and **0** turns panning off entirely. The gain
    also scales the fling, so a released pan coasts at the same visual rate it was moving.
- **Export PDF** — the menu's **Export PDF** flattens the whole document to a PDF: each page is
  drawn at its true size with its background (a PDF page or a ruled sheet) and every stroke and
  element merged on top, then written to the location you pick. When a page came from an **imported
  PDF**, its original page is **kept as vector content** and your annotations are laid over it as
  vectors too — so re-exporting an unchanged PDF stays about its original size and sharpness instead
  of ballooning from a rasterised copy. Use this to share an annotated copy; **Save** keeps the
  editable `.xopp`.
- **Save** — the menu's **Save** writes the whole document back out to a `.xopp` file, preserving
  every page, layer, background, and element — strokes plus the text, images, and LaTeX images you
  authored on-device. Save writes in whichever **format you last chose in Save As…** (see below):
  it starts as **Original**, and once you Save As **Zipped**, every later Save stays Zipped until
  you switch back. Opening a file adopts the format it was stored in.

- **Save As…** — the menu's **Save As…** opens a dialog to name the file and pick its format:
  - **Original (gzip)** — the standard Xournal++ `.xopp` (gzip-compressed XML). For a PDF-backed
    document the PDF stays **linked by location** (its path/URI), so the `.xopp` is small and
    reopening it reloads that PDF from where it lives — the interchange-safe default.
  - **Zipped (single file)** — one self-contained `.xopp` with the **PDF embedded inside** it, so
    the document is fully portable and moves as a single file. It reopens with its background intact
    in Xopp itself (the PDF travels in the same file) as well as on desktop Xournal++.
    - **Note — targeting release Xournal++ on Arch Linux.** The current released desktop Xournal++
      (1.3.5) has a bug in its ZIP reader that rejects a *correctly* labelled archive, so Xopp
      deliberately writes a slightly non-standard internal marker to open on that release. This is a
      temporary workaround; it will be reverted to the standard once upstream fixes the bug.

The file on disk is the only source of truth — there's no cloud, account, or custom format.

The file on disk is the only source of truth — there's no cloud, account, or custom format.

## Project layout

The authoritative layout lives in [`docs/architecture.md`](docs/architecture.md). In short:

```
app/src/main/java/com/xopp/android/
  format/      # .xopp read/write: model, colour codec, gzip, dependency-free XML layer
  render/      # stylus canvas, page layout/rendering, PDF import & export
  ui/          # Compose Material 3 editor screen, dockable toolbar rail pop-ups, settings, theme
  MainActivity.kt
app/src/test/  # JVM unit tests for the format and render layers
Dockerfile, compose.yaml, scripts/build.sh   # containerized build
```
