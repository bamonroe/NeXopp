# Architecture

Authoritative home for **how the system works internally**: the `.xopp` format mapping, the
read/write data path, the core components, the repository layout, and the load-bearing design
decisions. `CLAUDE.md` points here; the specifics live here.

> Status: the lossless `.xopp` format core and the Compose/`SurfaceView` editor are implemented,
> including PDF import and export. This doc is kept current as the code evolves; record design
> decisions here so they aren't re-litigated.

## Prior art — has someone already done this?

Surveyed 2026-07-30. **Conclusion: no maintained, native, full-fidelity `.xopp` editor for
Android exists.** The gap this project targets is genuinely open, and the community actively
asks for it. Details:

| Project | Read/Write | Tech | Status |
|---------|-----------|------|--------|
| [Xournal++ Mobile](https://gitlab.com/TheOneWithTheBraid/xournalpp_mobile) (mirror: [GitHub](https://github.com/xournalpp/xournalpp_mobile)) | Read **and** write, full `.xopp` | Flutter/Dart | **Archived 2025-08-27**; no real features since ~2021; never stable (author flagged stroke support as poor) |
| [Xournal++ viewer](https://f-droid.org/packages/de.thefeiter.xournalviewer/) | **Read-only** | Android | On F-Droid; view-only, cannot edit or save |
| [Linwood Butterfly](https://xournalpp.github.io/community/other-software/) | *Imports* `.xopp`; native format is its own | Flutter | Actively maintained, but **no lossless round-trip** — a different app that can read our files |
| [Termux + Termux-X11](https://github.com/xournalpp/xournalpp/discussions/5654) | The real desktop app | Linux-in-a-container | Works, pen pressure works, but a compatibility-layer hack, not a native app |

**Decision — build our own, learn from Xournal++ Mobile.** The one true round-trip attempt
(Xournal++ Mobile) reached full-format read/write and was then abandoned; its weak point was
exactly stroke fidelity, which is what our "round-trip safety" principle targets. We build
fresh on native Android (not Flutter), but treat that project as a **format reference**, not a
competitor.

### Reference clone

The archived Xournal++ Mobile source is cloned locally at **`reference/xournalpp_mobile/`**
(git-ignored — see `.gitignore`; not part of our build, kept only for reading code). It is
**EUPL-1.2** licensed — read it for the format mapping, but do not copy code into our
(differently-licensed, TBD) tree without clearing the license implications.

Its data model is the most useful artifact — a clean decomposition that mirrors the `.xopp`
structure:

- `lib/src/XppFile.dart` — top-level document: gzip (via the `archive` package) + XML (via the
  `xml` package) load/save; `XppFile → pages`.
- `lib/src/XppPage.dart`, `lib/src/XppLayer.dart` — page and layer containers.
- `lib/src/XppBackground.dart` — page backgrounds (plain/ruled/graph, PDF).
- `lib/layer_contents/` — the drawable content types: `XppStroke.dart`, `XppText.dart`,
  `XppImage.dart`, `XppTexImage.dart`.

## The `.xopp` format (code-derived — this is its authoritative home)

A `.xopp` file is a **gzip-compressed, UTF-8, XML document**. Uncompress with gzip and you get
a plain XML tree rooted at `<xournal>`. This section is the authoritative schema our
reader/writer must implement. It is derived from three sources: the real `udiff.xopp` sample in
the repo root (`xournalpp 1.1.1+dev`, `fileversion="4"`), the archived Xournal++ Mobile
reference clone, and desktop Xournal++'s writer. Where the sample and the reference disagree,
**the desktop file wins** — round-trip safety is measured against desktop Xournal++.

#### Two containers, one XML: gzip vs ZIP-package (`SaveFormat`)

The same `<xournal>` XML is written in one of two containers, chosen in the "Save As" dialog and
then made **sticky** — every later plain Save reuses the last-picked format (owned by
`format/SaveFormat.kt`, wired in `MainActivity`; opening a document adopts the format it was
stored in, sniffed from the first two bytes: `1f 8b` gzip vs `PK` zip).

- **`ORIGINAL`** — the legacy gzip `.xopp` (`format/Xopp.kt`, JDK `GZIPOutputStream`). A PDF
  background stays **linked by location** (`domain="absolute"`, its path/URI). The
  interchange-safe default desktop Xournal++ also writes.
- **`ZIPPED`** — a self-contained ZIP-package `.xopp` (`format/XoppZip.kt`) with the PDF
  **embedded inside** the archive. Entries: `mimetype`, `META-INF/version`
  (`current=<fileversion>\nmin=1`), `content.xml` (the same XML, plain — *not* gzipped), and the
  PDF as `bg.pdf` (referenced by `domain="attach"`, `filename="bg.pdf"` — an in-archive entry
  name, not a sibling path). Because the PDF travels inside the one file, a ZIPPED document
  reopens **in this app** with its background intact (no sibling to resolve).
  - **Intentional mimetype deviation (targeting release Xournal++ on Arch Linux).** The
    spec-correct mimetype is `application/xournal++`, but the *released* Xournal++ 1.3.5 (the
    Arch build the owner runs) has an **inverted** mimetype check in `LoadHandler`
    (`if (!strcmp(mimetype, "application/xournal++")) → "Mimetype wrong"`), so it *rejects* a
    correctly-labelled archive. We therefore deliberately write a non-canonical mimetype
    (`XoppZip.MIMETYPE = "application/x-xopp-zip"`), which its one-value check accepts. This is a
    known, temporary hack — flip `XoppZip.MIMETYPE` back to the canonical string once upstream
    fixes the check. Xournal++ enforces neither entry order nor per-entry compression.

### Units and coordinate system

- **All geometry is in points (pt), 1 pt = 1/72 inch.** Page size, stroke coordinates and
  widths, text position and size, and image/teximage bounding boxes are all pt.
- **Origin is top-left**, x increases right, y increases down. Values are written as decimals
  with 8 fractional digits (e.g. `162.27585752`), but any valid decimal parses.

### Color encoding

- On-disk form is `#RRGGBBAA` — 8 hex digits, **alpha last** (e.g. `#000000ff` opaque black,
  `#ffffffff` opaque white). Our writer emits this form.
- On **read** be lenient: also accept 6-digit `#RRGGBB` (implicit `ff` alpha) and the desktop
  named-color keywords (`black, blue, red, green, gray`/`grey`, `lightblue, lightgreen,
  magenta, orange, yellow, white`). Internally we store ARGB; only the byte order differs
  from Android's `0xAARRGGBB`, so convert on the boundary.

### Element tree

```
<xournal creator="…" fileversion="4">
  <title>…</title>                     (ignored on read; a fixed string on write)
  <preview>…base64 PNG…</preview>       (optional thumbnail; regenerated on write)
  <page width height>
    <background type style color … />
    <layer>
      <stroke …>…coords…</stroke>
      <text …>…text…</text>
      <image …>…base64…</image>
      <teximage …>…latex…</teximage>
    </layer>                            (1+ layers per page)
  </page>                              (1+ pages per document)
</xournal>
```

**`<xournal>`** — root. Attributes: `creator` (writer id string), `fileversion` (`"4"` for
current desktop). Children: optional `<title>`, optional `<preview>`, then 1+ `<page>`.

**`<title>`** — decorative text child; desktop writes a fixed banner string. Not parsed.

**`<preview>`** — inner text is a base64-encoded PNG thumbnail of page 1. Optional; we
regenerate it on write (or omit it — desktop tolerates its absence).

**`<page>`** — attributes `width`, `height` (pt). Contains exactly one `<background>` then 1+
`<layer>`.

**`<background>`** — empty element, attributes depend on `type`:
- `type="solid"`: `color` (hex or named), `style` ∈ `plain | lined | ruled | graph | dotted`.
- `type="pixmap"`: `domain` ∈ `absolute | attach | clone`, `filename` (image path/URI).
- `type="pdf"`: `filename` (PDF path/URI), `pageno` (**1-based** PDF page index, matching desktop
  Xournal++'s `SaveHandler`; converted to/from the 0-based `Background.Pdf.pageNo` used internally
  to index Android's `PdfRenderer` — see `XoppReader`/`XoppWriter`); `domain` on the first pdf
  background of the doc. The `domain` follows from the chosen `SaveFormat` (see the container
  section above), applied by `documentWithPdfDomain` in `render/PdfBackgroundDomain.kt`:
  - `domain="absolute"` — `filename` is the PDF's path/URI; the .xopp links to it in place (what
    the gzip `ORIGINAL` format writes). *(Desktop treats a relative `filename` as relative to the
    .xopp's folder and an absolute one as-is; we record the picked PDF's `content://` URI here.)*
  - `domain="attach"` — the PDF is bundled *inside* the `ZIPPED` container as the `bg.pdf` archive
    entry; `filename` is that in-archive name (`bg.pdf`), which desktop resolves via
    `readZipAttachment`. Self-contained and portable, and reopens with its background intact in this
    app (the PDF travels in the same file). *(The earlier gzip-plus-sibling attach — a
    `<xoppname>.bg.pdf` file written next to the .xopp — was replaced by this embedded form.)*
  - `domain="clone"` is **pixmap-only** in desktop Xournal++ (it reuses an earlier background image
    by id) and is never written for a PDF background, so the Save As dialog does not offer it.

**`<layer>`** — optional `name` (desktop's `<layer name="Layer 1">`; preserved on round-trip, null
when omitted). Children in document order: any mix of `<stroke>`, `<text>`, `<image>`, `<teximage>`.
**Document order is z-order** and must be preserved on round-trip. Layer *visibility* is **not** a
format attribute — it's a view-only editor state (a hidden layer still round-trips with its content).

**`<stroke>`** — the core drawable. Attributes:
- `tool` ∈ `pen | highlighter | eraser`. The highlighter renders distinctly from the pen: a
  broad, **constant-width** band (authored at ~6× the pen width, pressure-independent → a single
  `width` value) drawn as one **semi-transparent** path so its alpha doesn't bead at self-overlaps
  (`StrokePainter.drawBand`). Eraser strokes exist in the format but desktop rarely persists them.
- `color` — hex/named as above.
- `width` — **space-separated list of doubles (pt)**. The **first value is the nominal stroke
  width**; the remaining values (if present) are the **per-vertex pressure widths**. A single
  value means constant width. On read, if fewer widths than vertices, reuse the first for all.
- `capStyle` — `round` | `butt` | `square` (line cap; desktop attribute, default `round`).
- `style` — line pattern ∈ `plain | dash | dashdot | dot` (default `plain`, omitted when plain).
  Dashed/dotted strokes render as one constant-width path with a width-proportional dash pattern
  (`StrokePainter.dashIntervalsPt`, shared by screen and PDF export).
- `fill` — fill alpha `0..255` painted inside the closed stroke (shapes/highlighter fill), or absent
  for no fill.
- `ts`, `fn` — audio-recording timestamp / filename for pen-replay; `ts="0" fn=""` when
  unused. Preserve verbatim on round-trip; no rendering meaning for us.
- **Inner text**: a flat space-separated coordinate list `x0 y0 x1 y1 …` (pt). Vertex *i* is
  `(text[2i], text[2i+1])` and its width is `width[i+1]` (or `width[0]` if constant).

**`<text>`** — attributes `font` (a Pango-style font **description**: family plus optional
`Bold`/`Italic` tokens, e.g. `Sans Bold Italic` — parsed/composed by `format/FontDescription.kt`),
`size` (pt), `x`, `y` (pt, top-left anchor), `color`. **Inner text** is the string, XML-escaped
(`&amp; &lt; &gt;`). The description has no underline token, so underline is not representable.

**`<image>`** — attributes `left`, `top`, `right`, `bottom` (pt bounding box). **Inner text**
is base64-encoded raw image bytes (PNG/JPEG as stored).

**`<teximage>`** — a LaTeX-rendered image. Attributes `text` (LaTeX source), `color`, and
`left/top/right/bottom` bounding box (pt). Inner text duplicates the LaTeX source (escaped).

### Fidelity notes / round-trip hazards

- **Preserve unknown attributes.** Desktop emits attributes we don't render (`ts`/`fn`,
  possibly future ones); carry them through unchanged rather than dropping them.
- **Preserve layer child order** exactly (it is z-order).
- **Alpha matters** for highlighter — don't force `ff`.
- The Xournal++ Mobile reference is a *simplified* writer (always alpha `ff`, fixed title,
  skips erasers, pressure-as-width-list); use it for element names, not as the fidelity bar.

- [x] **Drift test in place.** `RealFileRoundTripTest` re-serializes `udiff.xopp`, and
      `FormatDriftTest` does the same over a committed fixture set
      (`app/src/test/resources/fixtures/`, each validated to load in desktop Xournal++ 1.3.5)
      while asserting the fixtures collectively cover this schema surface — all background
      styles, multi-page, layers, every element type, pressure vs. uniform width, highlighter
      alpha. A parser/writer change that would corrupt a real file, or drop a documented
      feature's coverage, fails the build (per `CLAUDE.md`'s code-derived-fact rule).
      `FormatDriftTest` covers only `Background.Solid` styles; the `pdf` background on-disk
      round-trip (filename+domain on page 1, `pageno`-only on later pages) is locked in
      separately by `PdfBackgroundRoundTripTest`, and the stroke `style`/`fill` attributes plus the
      `<layer name>` attribute by `StyleFillLayerNameRoundTripTest`.

## Stack — pinned 2026-07-30

Native Android, no cross-platform framework (the abandoned reference was Flutter; we go
native for stylus latency and platform fit).

- **Language:** Kotlin, targeting the modern Android SDK.
- **App chrome / UI:** **Jetpack Compose with Material 3** (Material You) for all app chrome —
  app bar, menus, dialogs, the tool palette. Satisfies the Material Design requirement in
  `TODO.toml`.
- **Drawing surface:** a custom low-latency **`SurfaceView`** (not Compose `Canvas`) hosted in
  the Compose tree via `AndroidView`. Stylus input comes from raw **`MotionEvent`** with
  `getPressure()` / `getAxisValue(AXIS_PRESSURE)` and historical points
  (`getHistoricalX/Y/Pressure`) so fast strokes keep their samples. This is the load-bearing
  choice for "round-trip safety" — we capture pressure at the same fidelity the format stores.
- **`.xopp` I/O — no third-party format libraries.** Both containers use only the JDK's
  `java.util.zip`: gzip via `GZIPInputStream` / `GZIPOutputStream` (`ORIGINAL`), and the
  ZIP-package via `ZipInputStream` / `ZipOutputStream` (`ZIPPED`, `format/XoppZip.kt`). XML goes
  through Android's built-in streaming `XmlPullParser` (read) and `XmlSerializer` (write).
  Streaming keeps large documents off the heap and gives us exact control over attribute
  preservation (a fidelity requirement above).
- **PDF export — PDFBox (`com.tom-roush:pdfbox-android`).** *Decision (2026-07-31):* the one
  non-framework runtime dependency, taken deliberately. The framework `android.graphics.pdf`
  writer (`PdfDocument`) can only paint onto a canvas, so exporting an imported PDF forced every
  page through a raster bitmap — a no-op import→export bloated files ~10× and discarded vector
  content. PDFBox is the only mature, permissively-licensed (Apache-2.0) library that can import
  an existing PDF page **preserving its vector content** and append a vector overlay; iText's
  AGPL licence ruled it out. Scope is contained to `PdfExporter`/`PdfVectorPainter`/
  `PdfBackgroundPainter`; the `.xopp` I/O layer above stays dependency-free, and display still
  uses the framework `PdfRenderer`.
- **File access:** the Storage Access Framework (`ACTION_OPEN_DOCUMENT` /
  `ACTION_CREATE_DOCUMENT`) so a `.xopp` opens/saves in place on the device — the file on disk
  is the only source of truth (per `CLAUDE.md` non-goals: no cloud, no custom format).
- **Build:** Gradle (Kotlin DSL) inside a **Docker** container (Podman fallback) per
  `CLAUDE.md`. Pipeline details live in `docs/tools.md`; build/run for a human lives in
  `README.md`.

## Data path

The core loop:

```
open (SAF Uri) → sniff container (gzip / ZIP) → GZIP|ZIP InputStream → XmlPullParser → Document model
   → render on SurfaceView (Material 3 chrome around it)
   → stylus edits mutate the model → XmlSerializer → GZIP|ZIP OutputStream (sticky SaveFormat) → save (SAF Uri)
```

Reading and writing are **streaming and symmetric**: the parser builds the model element by
element; the serializer walks the model in document order and re-emits it, carrying through any
preserved-but-unrendered attributes so the file round-trips.

### In-memory document model

The native-Android analogue of the reference's `Xpp*` types — one small Kotlin data
class/module per format element, mirroring the tree in [The `.xopp` format](#the-xopp-format-code-derived--this-is-its-authoritative-home):

- `Document` — `creator`, `fileversion`, optional title/preview, `List<Page>`.
- `Page` — `width`, `height` (pt), `Background`, `List<Layer>`.
- `Background` — sealed type: `Solid(color, style)`, `Pixmap(domain, filename)`,
  `Pdf(filename, pageNo, domain?)`.
- `Layer` — ordered `List<Element>` (z-order) plus optional `name`.
- `Element` — sealed type: `Stroke`, `Text`, `Image`, `TexImage`.
  - `Stroke` — `tool`, `color`, `capStyle`, `lineStyle` (plain/dash/dashdot/dot), `fill` (0..255 or
    null), `List<Point>` (each `x, y, width`), plus preserved raw attrs (`ts`, `fn`, unknowns).
  - `Text` — `font`, `size`, `x`, `y`, `color`, `content`.
  - `Image` — bbox `left/top/right/bottom`, decoded bytes.
  - `TexImage` — bbox, `latex`, `color`.

Colors are stored as Android `0xAARRGGBB` ints; the I/O layer converts to/from the on-disk
`#RRGGBBAA`. Coordinates are stored in pt (document space); the view applies a pan/zoom
transform to screen space.

This list mirrors the Kotlin types in `format/model/` — keep the two in sync when the model changes.

## Repository layout

A standard single-module Gradle (Kotlin DSL) Android project. The app code is split into small,
single-responsibility files per `CLAUDE.md`'s style guide.

```
settings.gradle.kts, build.gradle.kts, gradle.properties   # Gradle config
gradle/libs.versions.toml                                  # version catalog (all deps/plugins)
gradlew, gradle/wrapper/                                    # Gradle wrapper (pinned 8.9)
Dockerfile, compose.yaml, .dockerignore                    # containerized build image + service
scripts/build.sh                                           # docker/podman build entry point

app/
  build.gradle.kts                                         # module config (SDK levels, Compose, deps)
  src/main/AndroidManifest.xml
  src/main/res/                                            # strings, Material 3 theme, adaptive icon
  src/main/java/com/xopp/android/
    MainActivity.kt          # hosts the editor; bridges the Storage Access Framework to I/O
    format/                  # THE CORE — lossless .xopp read/write (pure Kotlin, no device deps)
      model/                 # Document, Page, Layer, Background, Element/Stroke/Text/Image/TexImage
      xml/                   # XmlPullReader, XmlWriter — the dependency-free XML layer
      XoppColor.kt           # #RRGGBBAA <-> ARGB int, named colours
      XoppReader.kt          # XML -> Document
      XoppWriter.kt          # Document -> XML
      Xopp.kt                # gzip open/save + parse/serialize entry points
      XoppZip.kt             # ZIP-package open/save (PDF embedded); see the mimetype caveat
      SaveFormat.kt          # ORIGINAL (gzip) vs ZIPPED (single-file) — the sticky save choice
    render/
      DrawingSurfaceView.kt  # low-latency stylus canvas (MotionEvent pressure)
      PageStacker.kt         # lays pages out top-to-bottom, fit to width (pure geometry)
      BackgroundGrid.kt      # ruling line/dot offsets in pt (pure geometry)
      BackgroundRenderer.kt  # paints a page background (plain/lined/ruled/graph/dotted, or a PDF page image)
      StrokePainter.kt       # paints a stroke's pressure polyline (shared by screen + PDF export)
      PageRenderer.kt        # draws a page's layers/elements at a scale/offset (shared)
      ElementRenderer.kt     # draws text boxes, images, and LaTeX images (real math)
      LatexParser.kt         # LaTeX source -> node tree (pure, no Android deps)
      LatexRenderer.kt       # draws a parsed LaTeX tree to a Canvas (fractions, scripts, roots)
      PdfPageCache.kt        # rasterises an imported PDF's pages to bitmaps (framework PdfRenderer)
      PdfImport.kt           # builds a Document of pdf-background pages from a PdfPageCache
      PdfText.kt             # positioned word model + grouping + range selection (pure, tested)
      PdfTextExtractor.kt    # pulls a PDF's positioned text layer via PDFBox PDFTextStripper
      PdfExporter.kt         # flattens a Document to a PDF (PDFBox; preserves source vector pages)
      PdfVectorPainter.kt    # draws a page's strokes/text/images as vector overlay onto a PDFBox stream
      PdfBackgroundPainter.kt # draws a fresh (non-PDF) page's background ruling as PDFBox vectors
      PdfPageTransform.kt    # maps .xopp top-left points into PDF bottom-left user space (pure)
      PdfOverlayMatrix.kt    # overlay cm-matrix that aligns annotations on /Rotate 90/180/270 pages (pure)
      TextBlock.kt           # text line-split + baseline geometry (pure)
      StrokeHitTester.kt     # whole-stroke eraser point-to-stroke hit geometry (pure)
      StrokeEraser.kt        # partial eraser: split a stroke into surviving pieces (pure)
      PageEraser.kt          # eraser applied to a page: mode, tip size, hidden-layer skip (pure)
      ShapeBuilder.kt        # line/arrow/rectangle/ellipse drag -> stroke vertex list (pure)
      LayerOps.kt            # add/delete/rename/reorder/move-selection layer edits (pure)
      ElementBounds.kt       # pt bounding box of any element + a Bounds value type (pure)
      Selection.kt           # ElementRef + SelectionTester: rect/tap picking, selection bounds (pure)
      SelectionOps.kt        # translate / delete selected elements on a page (pure)
      InputClassifier.kt     # pointer kind + button + active tool + settings -> gesture intent (pure)
      PressureCurve.kt       # pressure -> width multiplier + sensitivity presets (pure)
      Fling.kt               # decelerating two-axis momentum-scroll kinematics (pure)
      VelocityEstimator.kt   # pan release-velocity from a trailing sample window (pure)
      EditHistory.kt         # generic undo/redo over document snapshots (pure)
      PageOps.kt             # insert / delete pages in a page list (pure)
    ui/                      # Compose Material 3
      EditorScreen.kt        # top bar (undo/redo + ☰ overflow menu), left rail, canvas, author dialogs
      SideToolbar.kt         # left vertical rail: Tool/Colour/Size/Zoom/Pages button-anchored pop-ups
      ScrollThumb.kt         # right-edge PDF-style scroll thumb: drag to page fast, faint-when-idle, page bubble
      SettingsScreen.kt      # full-screen stylus settings (finger-draw, barrel, hover, pressure feel)
      AppSettings.kt         # AppSettings model + SettingsStore (SharedPreferences persistence)
      theme/                 # XoppTheme (Material You), Color
  src/test/java/com/xopp/android/format/                   # JVM unit tests for the format layer
  src/test/java/com/xopp/android/render/                   # JVM unit tests for layout/grid/LaTeX geometry
  src/androidTest/java/com/xopp/android/                   # on-device smoke test (load/draw/save/reopen)
```

The **`format/` package is the heart** and is deliberately free of Android dependencies so the
round-trip logic is fully unit-testable on the JVM (see `app/src/test/`). `render/` and `ui/`
are the Android-facing shell around it.

**Rendering (`render/`).** The `DrawingSurfaceView` holds the whole [Document] and renders every
page in a single vertical stack, each page scaled to fit the view width via `PageStacker` and
drawn with its background ruling (`BackgroundRenderer`, using the pure `BackgroundGrid` offsets)
plus all of its layers in z-order. The geometry (page placement, gridlines) is factored into
`PageStacker`/`BackgroundGrid` precisely so it's unit-testable off-device. **One finger draws**
(a new stroke lands on the top layer of the page under the touch) — or **erases** when the
Eraser tool is active, deleting every stroke the eraser disc touches (hit geometry in the pure,
tested `StrokeHitTester`), or **pans** when the Hand tool is active; **two fingers pan** in any
tool. A **zoom** factor multiplies the fit-to-width scale (`PageStacker` takes it as a parameter);
when a page is wider than the view the same pan gesture scrolls horizontally, and narrower pages
are centred in the content band (`PageBox.leftPx`). Zoom keeps the viewport-centre point roughly
fixed, and is clamped to 25%–800% (`DrawingSurfaceView.MIN_ZOOM`/`MAX_ZOOM`). Strokes and other
elements are re-rendered vectorially at the zoomed scale, so they stay sharp at any level; PDF
backgrounds are re-rasterised per zoomed width up to `PdfPageCache.MAX_RASTER_WIDTH` (4096 px),
beyond which the cached bitmap is upscaled to bound memory. **Add/remove page** edit the page list through the pure, tested `PageOps` (a new page
inherits the size and background of the page in view). Each draw, erase, add, or remove snapshots
the whole document into the pure, tested `EditHistory`, so the top-bar **undo/redo** steps one
gesture at a time (snapshots are cheap — immutable pages/layers share structure). The stack is
bounded at `EditHistory.DEFAULT_MAX_DEPTH` (200) steps, dropping the oldest once full; pan and zoom are
view-only and not recorded. Strokes are drawn by the view; text boxes, images, and LaTeX images
are drawn by `ElementRenderer` (text baseline geometry lives in the pure, tested `TextBlock`; image
bytes are decoded once and cached by element identity). A `<teximage>` carries only its LaTeX
source in the model, so it is parsed once (cached by element identity) by the pure `LatexParser`
into a node tree and drawn as **real math** by `LatexRenderer` — fractions (numerator over
denominator with a rule), super/subscripts (smaller and shifted), square roots (radical + vinculum),
and a Unicode table for Greek letters and common operators/relations; the tree is measured at a
reference size then uniformly scaled to fit the element's box. Any parse/draw failure falls back to
the raw source text, so a malformed formula can't crash a frame.

**Shapes, styles, partial eraser, layers.** The **shape tools** (Line/Arrow/Rectangle/Ellipse) turn a
one-finger drag into an ordinary constant-width pen stroke: `ShapeBuilder` (pure, tested) converts the
drag's start/end into a vertex list, previewed live and committed as one undoable stroke, so shapes
round-trip like any stroke. A **line style** (`plain`/`dash`/`dashdot`/`dot`) and a **fill** alpha ride
on the stroke the tool draws next; `StrokePainter` paints a dashed/dotted style as a single
constant-width dashed path and floods a fill under the outline, and `PdfVectorPainter` mirrors both for
export (a `setLineDashPattern` stroke and a `fill()` polygon). The **partial eraser** (`StrokeEraser`,
pure, tested) rubs out only the touched vertices and splits a stroke into its surviving pieces (each
inheriting the original's colour/style/fill), alongside the original whole-stroke delete
(`StrokeHitTester`). `PageEraser` (pure, tested) is the page-level driver both modes go through: it
walks the page's layers, **skips hidden ones** (you only rub out ink you can see) and returns `null`
when nothing was touched, so the surface skips the document rebuild and the undo snapshot. The mode
and the tip size (`EraserSize`: Fine/Medium/Thick, radius in **document pt** so it is zoom-invariant,
matching the desktop) are view flags on the surface. **Layer management** (`LayerOps`, pure, tested) adds/
deletes/renames/reorders layers and moves a selection between them (all undoable), while the *active*
layer (where new ink lands) and per-layer *visibility* are view-only editor state on the surface —
visibility just skips a layer in `PageRenderer.drawElements`, so it never touches the file. The UI for
all four lives in the rail's **Tool** (shapes), **Style** (line style / fill / eraser mode), and
**Layers** pop-ups (`SideToolbar`).

**Authoring non-stroke elements.** With the **Text**, **Image**, or **LaTeX** tool active, the
surface is in a *placement* mode (`placeKind`): a one-finger tap (not a drag) raises `onPlace` with
the page-local point, which `EditorScreen` turns into a keyboard dialog (text/LaTeX) or, for images,
an `onPickImage` callback up to `MainActivity`'s SAF picker. The chosen content is inserted via
`insertText` / `insertTex` / `insertImage` — each a single undoable edit appended to the page's top
layer. Tapping an existing text box reopens it for editing (clearing the content deletes it);
matched by element identity. The view keeps the loaded document intact and only appends/edits, so
every page, layer, and element round-trips through save.

**Selecting objects (`render/`).** The **Select** tool (`EditorTool.SELECT`, mapped to the surface's
`selectMode`) mirrors desktop Xournal++'s object selection. A one-finger **drag** draws a rubber-band
marquee and selects every element **wholly enclosed** by it (desktop's rectangle-select semantics); a
one-finger **tap** picks the single topmost element under the point. Selection is **per page** —
anchored to the page the gesture started on — and elements are addressed by position, not identity, via
`ElementRef(layerIndex, elementIndex)`: a move rewrites the element objects but never reorders them, so
the refs stay valid across a live drag. The picking is pure and JVM-tested — `ElementBounds.of` gives
each element's pt bounding box (strokes grown by half-width, images/teximages are their box, text is the
same rough content-extent metric used for tap-to-edit), `SelectionTester` does rect-containment / topmost
tap / union-bounds, and `SelectionOps` translates or deletes the addressed elements on a page list
(returning a new list; immutable pages/layers share structure so a snapshot stays cheap). Dragging inside
the selection outline translates the elements live (recomputing from the gesture-start document each
frame so there's no drift) and commits as **one undoable edit**; a floating **Delete / Deselect** bar
(`EditorScreen.SelectionActionBar`, shown via `onSelectionChanged`) deletes (undoable) or clears the
selection. The dashed outline and marquee are drawn by the view over the page stack. Two-finger pan still
works in Select mode (it abandons the in-progress selection gesture).

Beyond move/delete, the outline carries **four corner resize handles** (a uniform scale about the
opposite corner, `SelectionOps.scale`) and — for an all-stroke selection only — a **right-edge rotate knob**
(`SelectionOps.rotate`, which bakes the angle into stroke vertices). A **lasso** marquee
(`lassoMode`) selects everything wholly inside a traced polygon (`SelectionTester.inPolygon`),
alongside the rectangle. **Cut / copy / paste / duplicate** run through a view-held element clipboard
(`SelectionOps.elementsAt` + `addToTopLayer`, which reports the pasted refs so the copies are
selected); paste lands on the visible page. Dropping a move over a **different page** re-homes the
elements onto that page (`SelectionOps.moveToPage`, mapping through both pages' pt frames). The
floating action bar also **recolours / re-widths** the selection (`SelectionOps.restyle`). The scope
and round-trip reasoning for what rotate/resize can touch lives in
[Stylus & selection roadmap](#stylus--selection-roadmap).

**PDF (`render/`).** A `<background type="pdf">` page shows its PDF page as the background image:
`PdfPageCache` wraps the framework `PdfRenderer` (dependency-free, serialised — `PdfRenderer` is
not thread-safe — with a bounded, recycling bitmap cache keyed by page and target-width bucket) and
`BackgroundRenderer` draws the rasterised page; a `.xopp` whose PDF isn't present falls back to a
plain sheet. **Import PDF** (`PdfImport`, invoked from `MainActivity`) copies the picked PDF into
app cache and builds a fresh `Document` — one page per PDF page, sized from the PDF, with the
`filename`+`domain` on page 1 only and `pageno` thereafter (the desktop on-disk convention). **Export
PDF** (`PdfExporter`) flattens the document back out with **PDFBox** (`com.tom-roush:pdfbox-android`,
the one non-framework runtime dependency — see the note below): a `pdf`-backed page whose source PDF
is available (`PdfPageCache.source`, the cached import) is **imported verbatim so its original vector
content is preserved** (`PDDocument.importPage`), and the annotations are appended over it as a
**vector overlay** (`PdfVectorPainter`, an `APPEND`-mode content stream); every other page becomes a
fresh sheet whose background ruling is drawn as vectors (`PdfBackgroundPainter`) with the same
overlay. `PdfVectorPainter` mirrors the on-screen `StrokePainter`/`ElementRenderer` geometry at scale
1 — the `.xopp` unit == the PDF unit (1/72") — flipping y into PDF's bottom-left space via
`PdfPageTransform`; pen strokes taper per segment, the highlighter is one constant-width translucent
path, text uses the base-14 fonts, and images embed losslessly. **Nothing is rasterised** except
user bitmap images (already raster), so a no-op import→export round-trips a PDF at ~its original size
and fidelity instead of bloating ~10× from a raster flatten. **Rotated source pages** (`/Rotate`
90/180/270) are handled: since the on-screen renderer already applies `/Rotate`, annotations are
authored in the page's *visual* space, so `PdfExporter` pre-multiplies the overlay content stream by
a `PdfOverlayMatrix` (a pure, unit-tested `cm` matrix — the inverse of the display rotation, with the
crop-box origin folded in) that maps visual coordinates into the page's unrotated content space; the
viewer's `/Rotate` then cancels back to the drawn position, so strokes, text, and images all land
correctly. For `/Rotate 0` the matrix is just the crop-origin shift.

**PDF text selection.** An imported PDF's **text layer** is extracted on import (off the UI thread)
by `PdfTextExtractor` — a `PDFTextStripper` subclass that turns each positioned glyph into a
`CharBox` and groups them into `PdfWord`s (`PdfWordGrouper`, breaking on whitespace, wide gaps, and
line changes) — into a `PdfTextIndex` threaded to the surface via `setPdfTextIndex` (mirroring
`setPdfSource`). This reuses the **same PDFBox dependency** as export, so **no OCR engine** is needed
for born-digital PDFs; a scanned image-only page yields no words (`hasAnyText` false), and OCR for
that case is a tracked follow-up. Boxes are page-local top-left points (the `.xopp` frame), so they
map to the screen through a `PageBox` exactly like strokes. The **Select text (PDF)** tool
(`EditorTool.TEXT_SELECT` → `ActiveTool.TEXT_SELECT` → `GestureIntent.SELECT_TEXT`) drags to select
an inclusive reading-order word range: `beginTextSelect`/`textSelectMove` resolve pointer positions
to word indices (`PdfTextIndex.anchorWord`), the range is highlighted (`drawTextSelection`), and the
`TextSelectionBar`'s Copy puts the text on the Android system clipboard (`copyTextSelection`). The
selection is a **view-only** overlay derived from the PDF — it isn't part of the `.xopp` document, so
it doesn't affect round-trip (matching how desktop selects a PDF background's text).

**Chrome (`ui/`).** `EditorScreen` is the one editor screen (a `Row`): a top bar with undo/redo
icon buttons and a **☰ overflow menu** (`DropdownMenu`) holding Open, Import PDF, Export PDF, Save,
and Settings; a **left vertical rail `SideToolbar`** with five buttons — Tool, Colour, Size, Zoom,
Pages; and the canvas filling the rest. Each rail button owns its own `DropdownMenu`, so the pop-up
is anchored to that button (opening to the right of the rail) rather than filling the screen. The
Tool pop-up lists Pen / Highlighter / Eraser / Hand / Text / Image / LaTeX as a UI-level
`EditorTool` (Hand is view-only pan and Text/Image/LaTeX are placement modes, none a document tool,
so `EditorScreen.applyTool` maps them to the surface's `handMode` / `placeKind` and maps the three
drawing tools to the document `Tool`). The **Pages** pop-up is a page navigator: `Page N / M` with
◀ / ▶ to jump to the previous/next page (`goToPage` scrolls the stack; the surface reports the page
under the viewport centre via `onCurrentPageChanged`), plus Add / Remove page. A **right-edge scroll
thumb** (`ScrollThumb.kt`, overlaid on the canvas in a `Box` sibling of the `AndroidView`) gives
PDF-style fast paging: the surface reports its vertical scroll geometry via
`onScrollChanged(scrollY, totalHeightPx, viewportPx)` (all content px, already zoom-scaled), the thumb
sizes/positions itself from that ratio and **dragging it** drives `DrawingSurfaceView.scrollToY`. The
touch target is only the thumb *band* (a small region tracking the scroll position), not the full
right edge, so a stylus can still draw over the page's right margin everywhere but the thumb; the
thumb sits faint when idle, brightens after a scroll, and is brightest while dragged, showing a
page-number bubble beside it. A rounded grip "peninsula" bulges out of the thumb's centre (purely
visual — the whole band already catches touches) so there's an obvious finger-sized target to grab. It is a pure navigation affordance — no `.xopp` state, so nothing
round-trips. Choosing Settings
from the ☰ menu swaps in `SettingsScreen`. The fixed pen palette (`PEN_COLORS`, `PEN_WIDTH_LABELS`)
lives in `SideToolbar.kt`; the user-configurable pen widths and the editable custom colour are
persisted in `AppSettings`/`SettingsStore`, and the arbitrary-colour HSV/hex picker is in
`ColorPicker.kt`. The **Select** tool adds a rail entry and a floating action bar; its
mechanics are in [Selecting objects](#selecting-objects-render) above.

## Stylus & selection roadmap

The app is **stylus-first**. `DrawingSurfaceView.onTouchEvent` routes every pointer-down through the
pure `InputClassifier`, so the pen hardware — not the on-screen toolbar — decides what a gesture does,
matching desktop Xournal++. This section is the design home for the input layer and for finishing the
selection tool to desktop parity; work items are journaled in `TODO.toml` (via the `todo` skill).

**Stylus input — implemented.** Android reports the source of every pointer via
`MotionEvent.getToolType(pointerIndex)` (`TOOL_TYPE_STYLUS` / `_ERASER` / `_FINGER`) and stylus
side-buttons via `getButtonState()` (`BUTTON_STYLUS_PRIMARY`). The view maps those onto the
device-independent `PointerKind` and calls the pure, JVM-tested **`InputClassifier`** (`PointerKind` +
barrel-pressed + `ActiveTool` + `InputSettings` → `GestureIntent`), keeping the decision logic off the
Android surface so it's unit-testable (`InputClassifierTest`). Precedence, "pen hardware wins over the
toolbar":

1. **Eraser tip.** A `TOOL_TYPE_ERASER` pointer (the flipped-over tip) erases whatever the tool.
2. **Barrel button.** `BUTTON_STYLUS_PRIMARY` held on a stylus invokes a configurable action while
   held — default **erase**, or **select** / **none** (`BarrelAction`), whatever the on-screen tool.
3. **Finger-draw gate.** With the Settings **"finger draws"** toggle off, a finger on a *drawing* tool
   only pans (select/place/hand still work with a finger) — palm-safe writing on non-stylus devices.
4. Otherwise the on-screen tool's default intent.

**Palm rejection** is the stateful half, handled in the view around the classifier: the active
draw/erase gesture is *owned by a pointer id* (`gesturePointerId`) and only that pointer is sampled
(`addSamples` reads `pointerIndex`, never pointer 0), so a resting palm — a different pointer — can't
perturb the stroke. A stylus/eraser pointer arriving mid-gesture **takes over** any gesture a finger
started (`onPointerDown` → `abandonInProgress`), and once a stylus owns the stroke (`stylusOwner`)
extra finger/palm pointers are ignored rather than treated as a second-finger pan.

**Pressure** feeds width through the pure `PressureCurve` (`min + (max−min)·pressure^gamma`, with
`min = 0.25` chosen to match desktop Xournal++'s deeper taper); the `PressureSensitivity` presets
(Soft/Linear/Firm) pick the exponent (`PressureCurveTest`).

**Stroke smoothing** sits between the raw `MotionEvent` samples and those page points, in
`StrokeSmoother.kt`, and is what makes Android handwriting look like the desktop's rather than a
ragged polyline. Two pure pieces:

- `StrokeSmoother` — per-stroke streaming filter in **view pixels** (so it is zoom-independent),
  reset in `startStroke` and fed by `addSamples`. It exponentially smooths position (α 0.55) and,
  harder, pressure (α 0.3), then **decimates** samples that moved less than 1.6 px with less than
  0.02 pressure change. A decimated sample still advances the filter, so no drift accumulates; the
  newest sample of every batch is `force`d through so the drawn line always reaches the pen.
- `StrokeSimplifier` — Ramer–Douglas–Peucker pass run once in `commitCurrent` over the finished
  freehand points, dropping vertices within `TOLERANCE_PT` (0.35 pt) of their neighbours' chord.
  Shape-tool output is exact geometry and is exempt. Fewer vertices = smaller `.xopp` and fewer
  `drawLine` calls per redraw.

Both are covered by `StrokeSmootherTest`. **Hover** (`ACTION_HOVER_MOVE` from a
stylus, via `onHoverEvent`) draws a preview ring where the tip will land. All of these are settings in
`AppSettings`, persisted by `SettingsStore` (SharedPreferences) and pushed live onto the surface by
`EditorScreen.applySettings`; the on-device `StylusInputTest` drives synthetic tool-typed
`MotionEvent`s to prove the wiring (eraser tip, barrel erase, finger-draw gate, palm rejection).
`AppSettings` also carries the **default tool** (`DEFAULT_TOOL_CHOICES` — pen/highlighter/eraser/hand),
which seeds `EditorScreen`'s active-tool state so a document opens in the user's chosen mode.

**Momentum scrolling.** A pan feeds each focus sample to the pure `VelocityEstimator`; on release the
view runs the release velocity (content-space, opposite the finger, clamped to the platform max)
through `Momentum.seed` and seeds the pure `Fling` with the result, then drives a `Choreographer` frame
loop that decays the velocity exponentially, scrolls `scrollY`/`scrollX` by each frame's step (clamped
by `maxScrollY()`/`maxScrollX()`), and re-`render()`s. It stops when the speed drops below a threshold
or both axes pin to a bound; a fresh touch, cancel, or detach halts it at once. `Momentum.seed` sets
the seed magnitude to `strength · REFERENCE_SPEED_PX · curve.factor(speed / REFERENCE_SPEED_PX)`, where
the `MomentumCurve` (`LINEAR`/`QUADRATIC`/`CUBIC`/`EXPONENTIAL`, user-selectable, default `QUADRATIC`)
shapes how hard a fast flick is rewarded. Every curve is pinned to `factor(0)=0` and `factor(1)=1`, so
a reference-speed flick coasts at its own speed on any curve and only the fall-off below / take-off
above the reference changes — a tiny flick barely drifts while a fast swipe flies many pages (the wide
dynamic range a plain linear scale lacked). **Only a one-finger pan flings:** a two-finger release
resets the estimator when the first finger lifts (to dodge the focus-point jump), so its
near-motionless single-finger tail carries no momentum — the intended feel. Both `Fling` and
`VelocityEstimator` are Android-free (we roll our own estimator because `VelocityTracker` returns
nothing for the synthetic events used in tests), so the kinematics are unit-tested on the JVM
(`FlingTest`, `VelocityEstimatorTest`) and stay frame-rate independent. Because `render()` re-emits
`onScrollChanged`, the scroll thumb tracks the glide live. A `panSensitivity` gain (the
`PanSensitivity` object in `Fling.kt`, default `1.0`) multiplies each pan delta before it hits
`scrollX`/`scrollY` — 1 tracks the finger one-to-one, `<1` pans slower, `>1` faster, `0` freezes the
document — and the same factor scales the seeded release velocity so the fling coasts at the pan's
visual rate.

**Out of scope: tilt / orientation.** The `.xopp` format stores only per-vertex width — it has no
place for stylus **tilt / orientation** (`AXIS_TILT` / `AXIS_ORIENTATION`), so tilt-driven width
can't round-trip through the file and is **out of scope** per the project's scope rule (we only
build features the `.xopp` format can represent — see `CLAUDE.md`). None of the input layer changes
the file format — it's all input-layer behaviour, so it lives entirely in `render/`/`ui/` without
touching `format/`.

**Selection — desktop parity (shipped).** The tool now covers rectangle **and** lasso select,
tap-pick, move (including **across pages**), on-canvas **resize** (uniform, corner handles) and
**rotate** (top knob), **cut / copy / paste / duplicate**, and **recolour / re-width**. Every
transform is a pure `SelectionOps` op (`scale`/`rotate`/`restyle`/`moveToPage`/`addToTopLayer`
alongside `translate`/`delete`) and lasso containment is `SelectionTester.inPolygon`, all
JVM-tested. **Rotate is stroke-only by the scope rule:** a stroke bakes rotation into its vertex
coordinates and round-trips, but text/images have no rotation attribute and axis-aligned boxes, so
`rotate` leaves them untouched and the view shows the rotate knob only for an all-stroke selection.
Non-uniform resize is likewise avoided (a text box's font size is a single scalar), so resize is a
uniform scale that keeps every element representable.
