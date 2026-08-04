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
stored in, classified by `format/FileKind.kt` — see below).

#### What the open path accepts (`format/FileKind.kt`)

Open never trusts the file name: the picker is unfiltered (`*/*`, since `.xopp` has no registered
MIME type) and SAF hands back `content://` URIs with no reliable suffix. `FileKind.sniff()` reads
the first `MAGIC_BYTES` (512) off the buffered stream and rewinds it, so the same stream goes on to
the loader its verdict picks. The sample is far larger than any magic number because plain text has
no signature at all — it is recognised by the whole sample decoding as printable UTF-8:

| Magic | `FileKind` | Loaded as | Sticky `SaveFormat` |
|---|---|---|---|
| `PK` | `ZIP` | ZIP-package `.xopp` (`XoppZip.open`) — the PDF travels inside | `ZIPPED` |
| `1f 8b` | `GZIP` | gzip `.xopp` (`Xopp.open`), PDF background relinked by path/URI | `ORIGINAL` |
| `%PDF-` | `PDF` | fresh annotatable document, one page per PDF page (`PdfImport.documentFor`) | `ORIGINAL` |
| `<?xml` / `<xournal` | `XML` | uncompressed Xournal++ XML (`Xopp.parseXml`); saved back compressed | `ORIGINAL` |
| none, but the sample decodes as printable UTF-8 (tab/CR/LF allowed, leading BOM skipped) | `TEXT` | plain text (`.txt`, `.md`), typeset into a generated PDF-backed document (`io/TextImport.kt`) | `ZIPPED` |
| anything else (empty or binary) | `UNKNOWN` | rejected with an "Open failed" toast | — |

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
    the gzip `ORIGINAL` format writes). There is **no `relative` domain**: desktop carries a
    relative path under this same domain and resolves it against the .xopp's own folder
    (`LoadHandler::getAbsoluteFilepath`), using an absolute one as-is. See *Relative PDF
    references* below — a relative path is the portable form, so it is what we prefer to write.
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
- `ts`, `fn` — audio-recording offset (**milliseconds**) and sidecar **file name** for pen-replay;
  `ts="0" fn=""` when unused. Read/written as a pair by `audio/AudioAnnotation.kt`; any stroke we
  don't stamp keeps whatever the file had, verbatim. The named file is **not** in the `.xopp` — it
  is a `.wav` sidecar beside it (see *Audio* below). No rendering meaning.
- **Inner text**: a flat space-separated coordinate list `x0 y0 x1 y1 …` (pt). Vertex *i* is
  `(text[2i], text[2i+1])` and its width is `width[i+1]` (or `width[0]` if constant).

**`<text>`** — attributes `font` (a Pango-style font **description**: family plus optional
`Bold`/`Italic` tokens, e.g. `Sans Bold Italic` — parsed/composed by `format/FontDescription.kt`),
`size` (pt), `x`, `y` (pt, top-left anchor), `color`. **Inner text** is the string, XML-escaped
(`&amp; &lt; &gt;`). The description has no underline token, so underline is not representable.

**`<image>`** — attributes `left`, `top`, `right`, `bottom` (pt bounding box). **Inner text**
is base64-encoded raw image bytes (PNG/JPEG as stored).

**`<teximage>`** — a LaTeX-rendered image. Attributes `text` (LaTeX source), `color`, and
`left/top/right/bottom` bounding box (pt). **Inner text** is the base64-encoded PNG desktop
rendered from that source; we keep those bytes verbatim (`TexImage.data`) so they survive a
round-trip. Older files without a `text` attribute put the LaTeX source in the body instead.

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
- [x] **XML-equality drift test.** `XmlEqualityRoundTripTest` compares the *emitted XML* against
      each fixture's source XML (normalized for formatting, attribute order, number precision and
      the `<title>` boilerplate — see the test's doc), and asserts the writer is a fixed point.
      The model-equality tests above can't see a difference that both the reader and writer agree
      on; this one can, and it is what caught the dropped `<teximage>` PNG body.

## Stack — pinned 2026-07-30

Native Android, no cross-platform framework (the abandoned reference was Flutter; we go
native for stylus latency and platform fit).

- **Language:** Kotlin, targeting the modern Android SDK.
- **App chrome / UI:** **Jetpack Compose with Material 3** (Material You) for all app chrome —
  app bar, menus, dialogs, the tool palette. Satisfies the Material Design requirement in
  `TODO.toml`.
- **One colour scheme drives every surface.** All chrome — app bar, rail/toolbar, dialogs, the
  elevated popovers — takes its colours from `MaterialTheme.colorScheme` (`ui/theme/Theme.kt`);
  no surface hardcodes a colour. The canvas is the one exception by construction: it's a
  `SurfaceView` outside the Compose tree, so `ui/theme/ChromeColors.kt` maps the scheme onto its
  three chrome colours (page backdrop, selection marquee/handles, guide overlay) as ARGB ints and
  `EditorScreen` pushes them in via `DrawingSurfaceView.applyChromeColors`. **Ink, pen palette and
  page backgrounds are document data, not chrome, and are deliberately never themed** — they must
  round-trip to the file byte-for-byte.
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
open (SAF Uri) → UriStaging.stageIn (worker thread) → local staging file
   → FileKind.sniff (gzip / ZIP / PDF / XML / text) → GZIP|ZIP|plain InputStream → XmlPullParser → Document model
     (a raw PDF instead becomes a fresh document via PdfImport.adoptPdf/documentFor)
   → render on SurfaceView (Material 3 chrome around it)
   → stylus edits mutate the model → XmlSerializer → GZIP|ZIP OutputStream (sticky SaveFormat)
   → local staging file → UriStaging.stageOut (worker thread) → save (SAF Uri)
```

### Staging: remote (SSHFS/FTP/cloud) documents

The picker lists files on mounted network shares, and they arrive as ordinary `content://` URIs
from a remote `DocumentsProvider` — the only difference is **latency**. So every document transfer
is staged through a local file by `io/UriStaging.kt` — driven by `io/DocumentIo.kt`, which owns the
whole document-I/O policy (staging, both background-PDF stores, and the sniff/read, encode and merge
steps) so the activity is left with intent plumbing — and run off the UI thread by
`MainActivity.inBackground`, which shows the editor's blocking "Opening…/Saving…" overlay:

- **Read** — the bytes come down once into a staging file; sniff/parse/rasterise then work against
  a local file that can be re-read at will. A failed fetch closes the half-built tab and reports it.
- **Write** — the document is serialised locally *first* and only then pushed out in one pass, so a
  link that drops mid-encode can't leave a truncated `.xopp` on the far end.

Every staging file is allocated by `io/ScratchDir.kt` under a name **no other transfer reuses**, and
deleted by its caller once read. Since transfers run on worker threads, two of them overlap easily;
the fixed `open.tmp` this replaced meant a second, slower download overwrote the first document's
bytes before they were parsed, and both tabs came up holding the same document.

A document's **background PDF** gets its own file, allocated by `io/PdfStore.kt`. Whether it came
out of a ZIP package (`XoppZip.open`), was resolved from a `pdf` background reference
(`DocumentIo.resolvePdfBackground`) or was imported (`DocumentIo.adoptPdf`), the bytes land under a name no
other document uses, and that name is what `OpenTab.pdfPath` records. This is a correctness
requirement, not housekeeping: `PdfPageCache` rasterises through a file descriptor it holds open for
as long as the document is on a canvas, so a shared fixed name (the old `background.pdf`) let the
next document opened — in the other split pane, or another tab — overwrite the bytes underneath a
live renderer, and every page not yet rasterised came back blank. Since the files are never
rewritten, two views of one document (a mirrored tab) can share a path safely. As defence in depth
`PdfPageCache.checkSource` stamps the file's size and mtime when it opens the renderer and re-checks
on every request, re-opening (and dropping the whole cache) if the bytes were replaced anyway, and
closing itself — so the page draws with no background rather than white — if the replacement can't
be opened as a PDF. Unique names would
otherwise accumulate, so `MainActivity.prunePdfCache` sweeps the store against the paths the open
tabs *and* the live surfaces still reference, on every session persist and tab close.

The open picker asks for a **persistable read+write** grant (`OpenDocumentForEditing`), so plain
Save writes back to the tab's own `OpenTab.uri` (`MainActivity.saveActiveTab`) instead of asking for
a location, and a restored tab can still reach its file after a restart. The `CreateDocument` path
takes the same grant after a successful Save As.

Reading and writing are **streaming and symmetric**: the parser builds the model element by
element; the serializer walks the model in document order and re-emits it, carrying through any
preserved-but-unrendered attributes so the file round-trips.

### Tabs and the session cache

Several documents can be open at once, but there is only **one** `DrawingSurfaceView`. A tab switch
is therefore a swap, driven by `MainActivity`:

```
snapshot the surface into the outgoing OpenTab (document, save format, PDF path, page)
  → TabManager.select(index)
  → load the incoming OpenTab into the surface (setPdfSource → load → goToPage)
```

Consequences worth knowing:

- **Undo history is per surface, not per document.** It is cleared by `load`, so a tab switch starts
  the incoming document with a clean history. Content, including unsaved edits, is untouched.
- **Only the active tab's document is live**; every other tab holds the snapshot taken when it was
  last showing. That is also what gets written to disk.
- **The session is cached, not saved.** `TabStore` writes `filesDir/tabs/`: a `session.index` line
  file (`TabIndex`) plus one `<id>.xopp` gzip snapshot per tab, rewritten on every tab change and in
  `onPause`. On launch it is read back, so the app reopens on the same tabs with the same unsaved
  edits. It is a restart cache keyed by tab id — the user's own file (the tab's `uri`) is still the
  only thing the desktop ever sees, and a snapshot never stands in for saving.
- Tabs that were opened from a file keep that `content://` URI, so **Save** after a restart still
  writes back to the same document.

### Split view: the same thing, twice

Split view is modelled as **panes**, not as a second mode. An `EditorPane` (`panes/EditorPane.kt`)
bundles everything that used to be a per-activity singleton — the canvas, a `TabManager`, the sticky
`SaveFormat`, the pending save name and its own `TabStore` directory. `MainActivity` holds a fixed
list of two of them plus an `activePane` index; `surface`, `tabs`, `saveFormat` and `pendingSaveName`
are now *accessors* onto the pane in focus, which is why the open/save/import/audio code above is
still written against "the" document and needed no changes.

The design decisions worth keeping:

- **One pane has focus; the chrome drives that pane.** A touch anywhere in a pane (observed on the
  pointer-input *initial* pass, so the canvas still receives the event) makes it active. There is no
  second toolbar and no per-pane menu — the single top bar and rail always act on the focused pane.
- **Each pane persists separately.** `TABS_DIRS` gives pane 0 the historical `filesDir/tabs` and pane
  1 `filesDir/tabs-right`, so an existing session still restores and the right-hand pane's documents
  survive both a split-view toggle and a restart. `onPause` snapshots and writes *both*.
- **A pane's Compose mirror is per pane too.** `ui/PaneState.kt` holds the zoom/page/layer/undo state
  a canvas pushes up through its callbacks; `EditorUiState` keeps one per pane and `EditorScreen`
  hands the active one to each region. Each surface's callbacks write into *its own* `PaneState`, so
  a background pane stays current instead of scribbling over the focused one.
- **Turning split view off doesn't destroy the pane.** Its canvas is disposed, so when it comes back
  `restoreTabs` finds a non-empty session and re-loads the showing tab onto the *replacement*
  surface. (Undo history is per surface, so it does not survive that round trip — same rule as a tab
  switch.)
- **A document can be open in both panes as two live views.** Tabs carry an `OpenTab.docKey`; the
  mirror action copies a tab keeping that key, so "same key" means "same document". Every edit
  reaches the other views through `panes/MirrorSync.kt`: `DrawingSurfaceView.doc` is a property whose
  *setter* fires `onDocumentEdited`, so the one place every edit lands is also the one place the
  mirror is notified — no per-operation hooks to keep in sync. `MirrorSync` writes the new document
  into every tab record holding the key (background tabs included, so selecting one is already
  current) and calls `applyMirroredDocument` on any pane showing one. That entry point deliberately
  touches *nothing* but the document — scroll, zoom and columns are left alone, which is what makes
  the two views independent — and clears the receiving surface's undo history, since its snapshots
  predate the other view's edit and undoing to one would discard it. `load`/`applyMirroredDocument`
  write the backing field directly rather than the property, which is what stops an echo loop.
- **Which tabs are the same document is shown, not inferred.** `tabs/DocColors.kt` assigns a palette
  colour to every `docKey` open more than once across *both* panes, and `TabStrip` draws it as a dot;
  titles are file names, so they cannot distinguish "open twice" from "two files, one name".
- The split position (`SplitLayout` in `ui/`) is a UI-only fraction: dragged, clamped to leave each
  half at least 15% of the width, and deliberately **not** persisted.

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
  - `TexImage` — bbox, `latex`, `color`, and the rendered PNG bytes (`data`, nullable).

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
    MainActivity.kt          # hosts the editor; SAF intent plumbing over io/DocumentIo.kt
    format/                  # THE CORE — lossless .xopp read/write (pure Kotlin, no device deps)
      model/                 # Document, Page, Layer, Background, Element/Stroke/Text/Image/TexImage
      xml/                   # XmlPullReader, XmlWriter — the dependency-free XML layer
      XoppColor.kt           # #RRGGBBAA <-> ARGB int, named colours
      XoppReader.kt          # XML -> Document
      XoppWriter.kt          # Document -> XML
      Xopp.kt                # gzip open/save + parse/serialize entry points
      XoppZip.kt             # ZIP-package open/save (PDF embedded); see the mimetype caveat
      SaveFormat.kt          # ORIGINAL (gzip) vs ZIPPED (single-file) — the sticky save choice
      FileKind.kt            # content sniffing for open: ZIP / GZIP / PDF / XML / TEXT / UNKNOWN
    io/                      # storage access that isn't format work
      UriStaging.kt          # stage document bytes to/from a content:// URI (slow remote shares)
      ScratchDir.kt          # unique-per-call staging file names, so overlapping opens can't collide
      PdfStore.kt            # one background-PDF file per open document; never rewritten, content-cache index, byte-budget eviction
      TextImport.kt          # text file -> generated background PDF, cached by content hash
      PdfReference.kt        # how a .xopp names its background PDF: relative <-> absolute paths and SAF document ids
      DocumentIo.kt          # document I/O policy: staging + PDF stores + read/encode/merge
    panes/                   # split view: one or two editing panes, each with its own tabs
      EditorPane.kt          # one pane: canvas + tab session + save format + its own TabStore
    tabs/                    # several documents open at once, cached across app restarts
      OpenTab.kt             # one open document: id, title, source URI, save format, PDF, page
      TabManager.kt          # the tab list + selection rules (pure; no Android, no canvas)
      TabIndex.kt            # the session index text format (pure encode/decode)
      TabStore.kt            # filesDir/tabs: index + one .xopp snapshot per tab
    render/
      DrawingSurfaceView.kt  # low-latency stylus canvas (MotionEvent pressure)
      CanvasChrome.kt        # the canvas's non-document brushes: selection, band, guide, overview, hover, palette
      ViewportState.kt       # scroll offsets, zoom, and their clamps (pure, tested)
      MomentumDriver.kt      # the fling loop: velocity tracking, release seed, per-frame glide
      PageOverview.kt        # the overview grid's view state: edit mode, selection, clipboard, lift
      PageCommands.kt        # the page/layer edit commands and the two undoable commit pipelines
      SelectionGestureController.kt # the marquee/lasso pick and the move/resize/rotate drags
      VerticalSpaceDrag.kt   # the vertical-space tool's live grab-line drag
      GuideDrag.kt           # the setsquare/compass pose and the finger that moves it
      TextEditController.kt  # placing/editing text boxes, images and LaTeX images from a tap
      ElementEdits.kt        # the document edits behind those placements (pure, tested)
      PageStacker.kt         # lays pages out in rows of N columns, fit to column (pure geometry)
      BackgroundGrid.kt      # ruling line/dot offsets + the pt spacings themselves (pure geometry)
      Snapping.kt            # shape endpoints -> the ruling; rotation -> 15-degree steps (pure)
      DrawingGuide.kt        # setsquare/compass overlay geometry: project a drawn point onto an edge (pure)
      DrawingGuide.kt        # setsquare/compass overlay geometry + edge projection (pure)
      BackgroundRenderer.kt  # paints a page background (plain/lined/ruled/graph/dotted, or a PDF page image)
      StrokePainter.kt       # paints a stroke's pressure polyline (shared by screen + PDF export)
      PageRenderer.kt        # draws a page's layers/elements at a scale/offset (shared)
      ElementRenderer.kt     # draws text boxes, images, and LaTeX images (real math)
      RadialPaletteRenderer.kt # paints the open radial palette (two rings + hovered slot) over the canvas
      LatexParser.kt         # LaTeX source -> node tree (pure, no Android deps)
      LatexRenderer.kt       # draws a parsed LaTeX tree to a Canvas (fractions, scripts, roots)
      PdfPageCache.kt        # rasterises an imported PDF's pages to bitmaps (framework PdfRenderer)
      BitmapBudget.kt        # the one memory bound every bitmap cache allocates through
      PdfImport.kt           # builds a Document of pdf-background pages from a PdfPageCache
      PdfMerger.kt           # joins two PDFs end-to-end (PDFBox) so Append has one background PDF
      PdfText.kt             # positioned word model + grouping + range selection (pure, tested)
      PdfTextExtractor.kt    # pulls a PDF's positioned text layer via PDFBox PDFTextStripper
      PdfExporter.kt         # flattens a Document to a PDF (PDFBox; preserves source vector pages)
      PdfVectorPainter.kt    # draws a page's strokes/text/images as vector overlay onto a PDFBox stream
      PdfBackgroundPainter.kt # draws a fresh (non-PDF) page's background ruling as PDFBox vectors
      PdfPageTransform.kt    # maps .xopp top-left points into PDF bottom-left user space (pure)
      PdfOverlayMatrix.kt    # overlay cm-matrix that aligns annotations on /Rotate 90/180/270 pages (pure)
      TextBlock.kt           # text line-split + baseline geometry (pure)
      TextPaginator.kt       # text-import word-wrap + A4 pagination, injected measurement (pure)
      TextWrapping.kt        # tab expansion + mid-word hard break shared by both wrappers (pure)
      PdfFonts.kt            # embeds the bundled Unicode fonts (DejaVu) into a PDDocument, cached per doc
      TextPdfGenerator.kt    # authors the text-import PDF: selectable embedded text, injected font loader
      TextFlavor.kt          # plain vs markdown typesetting flavour + its PdfStore cache prefix
      markdown/
        MarkdownBlock.kt     # the block tree a markdown import lays out from (pure data model)
        MarkdownLine.kt      # line-level "what does this line start?" recognisers (pure)
        MarkdownParser.kt    # markdown source -> block tree, line-based recursive descent (pure)
        StyledRun.kt         # a stretch of text with one style combination (bold/italic/code)
        MarkdownInlineParser.kt # raw inline source -> styled runs (pure); scanner + emphasis pass
        InlineScanner.kt     # escapes, code spans and link labels; emits unresolved delimiter runs
        InlineEmphasis.kt    # pairs the delimiter runs into bold/italic (delimiter-stack walk)
        StyledWrapper.kt     # styled runs -> lines of positioned fragments, per-style metrics (pure)
      GlyphSanitizer.kt      # maps codepoints a font can't encode onto a substitution glyph (pure)
      StrokeHitTester.kt     # whole-stroke eraser point-to-stroke hit geometry (pure)
      StrokeEraser.kt        # partial eraser: split a stroke into surviving pieces (pure)
      PageEraser.kt          # eraser applied to a page: mode, tip size, hidden-layer skip (pure)
      ShapeBuilder.kt        # line/arrow(s)/rect/ellipse/axis drag -> stroke vertex list (pure)
      ShapeRecognizer.kt     # freehand stroke -> the primitive it resembles, or null (pure)
      SplineBuilder.kt       # spline control points -> cubic-Bezier stroke vertex list (pure)
      LayerOps.kt            # add/delete/rename/reorder/merge-down/move-selection layer edits (pure)
      ElementBounds.kt       # pt bounding box of any element + a Bounds value type (pure)
      Selection.kt           # ElementRef + SelectionTester: rect/tap picking, selection bounds (pure)
      SelectionOps.kt        # translate / delete selected elements on a page (pure)
      VerticalSpaceOps.kt    # insert / remove vertical space on a page, shifting what's below (pure)
      InputClassifier.kt     # pointer kind + button + active tool + settings -> gesture intent (pure)
      PressureCurve.kt       # pressure -> width multiplier + sensitivity presets (pure)
      Fling.kt               # decelerating two-axis momentum-scroll kinematics (pure)
      VelocityEstimator.kt   # pan release-velocity from a trailing sample window (pure)
      EditHistory.kt         # generic undo/redo over document snapshots (pure)
      PageOps.kt             # insert / copy / move / delete pages in a page list (pure)
    audio/                   # audio-annotated strokes: record, replay, sidecar transfer
      AudioAnnotation.kt     # AudioRef <-> a stroke's fn/ts attrs; document sidecar set (pure)
      WavWriter.kt           # streaming 16-bit PCM RIFF/WAVE writer, header patched on close (pure)
      AudioRecorder.kt       # AudioRecord capture thread -> WavWriter; byte-accurate elapsed clock
      AudioPlayer.kt         # MediaPlayer wrapper: play one clip from an offset
      AudioStore.kt          # app-private recordings dir + SAF tree import/export of sidecars
      AudioSession.kt        # the editor's single audio facade (record / stamp / play / sync)
    ui/                      # Compose Material 3
      EditorScreen.kt        # the editor's assembly: scaffold + body layout (rail edge, split panes)
      EditorUiState.kt       # the screen's remembered chrome state (pen, open dialogs, panes) in one holder
      EditorRegions.kt       # the screen's regions: top bar, ☰ menu, the rail's wiring, one pane's canvas
      EditorOverlays.kt      # what layers over the canvas: selection bars + author/save/import dialogs
      SideToolbar.kt         # left vertical rail: the shell + tool-group slots; each pop-up is a Toolbar*.kt below
      EditorTool.kt          # the editor's tool modes + their labels/icons (pure)
      ToolbarColorPopup.kt   # rail slot: the pen colour drop-down (wraps ColorPalette.kt)
      ToolbarSizePopup.kt    # rail slot: the three pen-width slots + their long-press resize dialog
      ToolbarPresetsPopup.kt # rail slot: the saved tool presets — activate, save-current, reorder, delete
      ToolbarStylePopup.kt   # rail slots: line style + fill controls, and the shape-recognition toggle
      ToolbarViewPopups.kt   # rail slots: zoom, page background, drawing guides, audio
      ToolbarPagesPopup.kt   # rail slot: page navigation/clipboard, overview grid controls, page-size dialog
      ToolbarLayersPopup.kt  # rail slot: the layer manager list + rename dialog
      ColorPalette.kt        # the one colour picker (swatches + custom slot + recents) all three sites use
      ColorPicker.kt         # the arbitrary-colour HSV/hex dialog behind the palette's custom slot
      RadialPalette.kt       # the pen-tip radial menu's model: two rings of slots holding PaletteActions (pure)
      RadialPaletteHitTest.kt # maps a flick's (angle, radius) from the anchor onto a slot, or cancel (pure)
      RadialPaletteLayout.kt # where the menu draws: anchor clamped on screen, slot mark centres (pure)
      RadialPaletteLabel.kt  # a slot's face: short glyph, or the swatch a colour slot fills with (pure)
      RadialPaletteCodec.kt  # one palette (and the whole list) as one SharedPreferences line; forgiving decode (pure)
      PaletteList.kt         # PaletteSet: add/rename/reorder/delete a palette + the active index, and the pre-list migration (pure)
      PaletteManagerRow.kt   # settings UI: the palette chips + add/rename/reorder/delete/activate buttons
      PaletteActions.kt      # runs a picked PaletteAction against the editor state + surface (the toolbar's edits)
      ToolPreset.kt          # a named snapshot of the whole tool config (tool/colour/width/style/fill); capture + apply
      ToolPresetList.kt      # save/overwrite, reorder and delete on the saved preset list (pure)
      ToolPresetCodec.kt     # the preset list's one-line SharedPreferences form; forgiving decode (pure)
      ToolGroups.kt          # the rail's tool groups + their persisted per-slot selections (pure)
      RailItems.kt           # the rail's button positions + their persisted order/hidden set (pure)
      ScrollThumb.kt         # right-edge PDF-style scroll thumb: drag to page fast, faint-when-idle, page bubble
      SettingsScreen.kt      # settings index: one clickable row per section, each opening its own page
      SettingsSections.kt    # the section bodies (Stylus / Editor / Toolbar / Navigation / Storage) + shared controls
      AppSettings.kt         # AppSettings model + SettingsStore (SharedPreferences persistence)
      theme/                 # XoppTheme (Material You), Color
  src/test/java/com/xopp/android/format/                   # JVM unit tests for the format layer
  src/test/java/com/xopp/android/render/                   # JVM unit tests for layout/grid/LaTeX geometry
  src/test/java/com/xopp/android/audio/                    # JVM unit tests for fn/ts mapping + WAV framing
  src/androidTest/java/com/xopp/android/                   # on-device smoke test (load/draw/save/reopen)
```

The **`format/` package is the heart** and is deliberately free of Android dependencies so the
round-trip logic is fully unit-testable on the JVM (see `app/src/test/`). `render/` and `ui/`
are the Android-facing shell around it.

**Rendering (`render/`).** Every repaint is **paced to the display**: `DrawingSurfaceView.render()`
never paints inline, it flags a `Choreographer` frame callback that runs the actual `paint()` once
per vsync, collapsing everything requested in between. This matters because a digitiser reports far
faster than the panel refreshes (240 Hz against 120 Hz on the large tablets): painting straight from
the input handler posted several buffers per vsync, and the compositor latching whichever was newest
made the shown position walk back and forth between samples instead of advancing — the flicker seen
when zoomed in on a big screen, where a paint is slow enough to keep several buffers in flight. The
fling loop is the one exception: it is already inside a frame dispatch, so it calls `paint()` directly
rather than deferring a frame — and because it does, `render()` is a **no-op while a fling is in
flight** (and starting a fling cancels any already-queued paint). Otherwise anything that asks for a
repaint mid-glide — most often a PDF tile landing and calling back, which is constant when zoomed in —
would post a *second* buffer for the same vsync and reintroduce the very buffer-walk flicker the
pacing exists to prevent. Each frame locks the surface with **`lockHardwareCanvas()`**, not
`lockCanvas()` (falling back to the software canvas only if the GPU one is unavailable): the software
canvas rasterises and blends every window pixel on the CPU, so frame cost scaled with window *area* —
full-screen page-flicking on a large tablet crawled while the identical gesture in a half-size
split-screen window stayed smooth. On the GPU canvas fill rate is effectively free and the cached page
bitmaps are plain textured blits. The `DrawingSurfaceView` holds the whole [Document] and renders every
page in a single vertical stack, each page scaled to fit the view width via `PageStacker` and
drawn with its background ruling (`BackgroundRenderer`, using the pure `BackgroundGrid` offsets)
plus all of its layers in z-order. The geometry (page placement, gridlines) is factored into
`PageStacker`/`BackgroundGrid` precisely so it's unit-testable off-device. **One finger draws**
(a new stroke lands on the top layer of the page under the touch) — or **erases** when the
Eraser tool is active, deleting every stroke the eraser disc touches (hit geometry in the pure,
tested `StrokeHitTester`), or **pans** when the Hand tool is active; **two fingers pan** in any
tool. A **zoom** factor multiplies the fit-to-width scale (`PageStacker` takes it as a parameter);
when a page is wider than the view the same pan gesture scrolls horizontally, and narrower pages
are centred in the content band (`PageBox.leftPx`). A **column count** (`PageStacker.stack(columns=)`,
driven by `DrawingSurfaceView.setColumns` and persisted as `AppSettings.pageColumns`) turns that stack
into the **page overview**: pages are chunked into rows of N, each fit to `viewWidth/N` and the row
centred, so 1 is the plain stack and 2-4 a grid of page thumbnails. Because a row now holds several
pages, hit-testing takes both axes — `StackedLayout.pageAt(x, y)` picks the page under a touch and
`nearestPage(x, y)` resolves a probe that lands in a gap (e.g. the viewport centre). Those two hit-tests
also drive the overview's **edit mode**. The grid has two modes, held in
`PageOverview.editMode` (view-only, default off, toggled from the Pages menu via
`setPagesEditMode`): in **view mode** the grid is display/navigation only — a confirmed Hand-tap
`goToPage`s the page it hit and neither selection nor reordering is armed; **edit mode** enables the
page tooling below, and leaving it cancels any lift and clears the selection. In edit mode those
hit-tests drive **drag-to-reorder**: a finger long-press (`ViewConfiguration`'s timeout,
disarmed by touch-slop travel or a second pointer, and never armed for a stylus or at one column)
lifts the page under it, the drag tracks a drop slot with `nearestPage`, and the release commits one
undoable `PageOps.move(pages, from, to)` through `editPages`. The same `pageAt(x, y)` hit-test drives
**multi-select delete**: at more than one column a confirmed Hand-tool tap (the double-tap tracker's
tap, rather than a second gesture) toggles the page under it in `PageOverview.selected`,
and `deleteSelectedPages()` commits one `PageOps.removeAll(pages, indices)` through `editPages`.
`removeAll` refuses a selection covering every page, so the document is never emptied. The same
selection feeds **copy/paste**: `copySelectedPages()` stashes `PageOps.copyOf(pages, indices)` (the
picked pages in ascending order) in the view-only `PageOverview.clipboard`, and `pasteCopiedPages()` commits
one `PageOps.insertAfter(pages, after, clipboard)` through `editPages`, inserting after the
highest-numbered selected page (or `currentPageIndex()` when nothing is picked). Pages are immutable,
so a "copy" is a shared reference — the duplicate carries the same strokes, layers, size and
background object, and `XoppWriter` serialises each page position independently, so the paste
round-trips with no deep copy needed. The selection
is view-only (never written) and is cleared by `editPages` and by dropping back to one column, since
both invalidate page indices; the clipboard is not cleared, so one copy can be pasted repeatedly. Page order *is* list order in
`Document.pages` — `XoppWriter` writes no index — so the reorder round-trips by construction. Zoom keeps the viewport-centre point roughly
fixed, and is clamped to 25%–1000% (`DrawingSurfaceView.MIN_ZOOM`/`MAX_ZOOM`). Strokes and other
elements are re-rendered vectorially at the zoomed scale, so they stay sharp at any level; PDF
backgrounds are re-rasterised per zoomed width up to `PdfPageCache.MAX_RASTER_WIDTH` (4096 px) and
never above `PdfPageCache.PER_PAGE_SHARE` of the cache budget for one bitmap (so the visible pages
can't evict one another and flash blank), beyond which the whole-page bitmap is upscaled to bound
memory — asynchronously, so a zoom step shows the previous resolution stretched and sharpens a
moment later rather than stalling the frame. A page with *nothing* cached is the one exception: it
rasterises inline, since an empty background reads as a blank page. Past that whole-page ceiling
the sharpness comes from **tiles**: `PdfPageCache.requestTiles` rasterises only the visible cells of
a `PdfPageCache.TILE_PX` (512 px) grid built at the true on-screen page width, each rendered 1:1 via
a `Matrix` on `PdfRenderer.Page.render`, and `BackgroundRenderer` draws them over the upscaled page
bitmap. So PDF text stays sharp to the 1000 % zoom ceiling while cost stays proportional to the
viewport, not the page; a tile that hasn't rasterised yet simply shows the coarse layer underneath.
When the tiles on hand already cover every visible pixel of the page, `BackgroundRenderer` skips the
coarse whole-page blit entirely, so those pixels aren't rasterised twice. Four things keep the tile
path off the frame budget: `requestTiles` **memoises** its answer per page and rebuilds the list only
when the visible cell block or the cache's contents change (a pan holds the same block for many
frames); it queues the **ring of cells just outside** the viewport, so a pan meets rasterised tiles
at its leading edge rather than the coarse under-layer; a viewport spanning more cells than
`PdfPageCache.MAX_TILES_PER_FRAME` caps how many new cells are *queued* instead of dropping the whole
request, so cells already rasterised still draw sharp; and `nearest` finds a stand-in bitmap through
a per-page sorted width index rather than scanning every entry, since a pinch calls it per visible
page per frame while the cache holds hundreds of tiles. Tiles landing from the worker coalesce into
a single redraw (`DrawingSurfaceView.requestRender`) instead of one full repaint each.
Eviction **pins the visible cells**. `requestTiles` records the viewport's cell block per page (and
refreshes its LRU recency) *before* the memo short-circuit, and `put` spares those keys on its first
eviction pass; the ring is only warmed while the cache is under `PdfPageCache.PREFETCH_HEADROOM` of
budget. Without this, a zoom whose visible tiles plus ring outgrow the budget evicts the very tiles
being drawn, the next frame falls back to the upscaled whole-page bitmap and re-queues them, and the
page flickers between blurry and sharp indefinitely. `DrawingSurfaceView` calls `PdfPageCache.retain`
each frame with the on-screen pages so pins don't accumulate behind a scroll, and a second eviction
pass ignores pins entirely, so a viewport too large to cache still stays memory-bounded.
Ink is culled the same way: `DrawingSurfaceView` hands `PageRenderer.drawElements` the viewport in
page-local pt, and any element whose `ElementBounds` box misses it is never submitted (boxes are
memoised by element identity, so the cull doesn't rescan stroke points each frame). At high zoom a
page spans many screens, where almost every stroke would otherwise cost thousands of canvas calls
Skia only clips away. `PdfExporter` passes no viewport and so draws everything. Above that cull sits
`InkCache`: each visible page's ink is rasterised once into an off-screen bitmap, so a pan or fling
frame is a **blit** rather than a re-submission of every stroke. The raster is keyed by a **zoom
bucket** (widths step by `InkCache.BUCKET_RATIO`, 1.19×), so a pinch only re-rasterises when it
crosses a bucket edge and the zooms in between are a ≤19 % stretch of the bitmap it already has. An
entry is invalidated by page identity (any edit rebuilds the `Page`), by its hidden-layer set, or by
scrolling out of view (`InkCache.retain` keeps only the visible pages). The cache **declines** two
cases and the direct element path takes over: a page whose bucket would exceed its per-entry ceiling
(`InkCache.PAGE_SHARE` of the shared budget — at deep zoom a page spans many screens and its full
raster would dwarf the screen it feeds, and the viewport cull is the better tool there), and any
gesture that rewrites the page every frame — drag, resize, rotate, erase — where caching would only thrash.
Both bitmap caches allocate through **one** `BitmapBudget` (`BitmapBudget.shared`, sized at startup
from `ActivityManager.memoryClass`), so a PDF-backed document has a single memory bound rather than
two independent guesses. A cache `charge`s each bitmap it rasterises; when the total goes over, the
budget asks its clients to `trim` — the *other* clients first, the one that just allocated last, so
the pixels being drawn this frame survive. `InkCache.trim` gives back off-screen pages first,
`PdfPageCache.trim` its least-recently-used entries (pinned tiles last). Trimmed bitmaps are dropped
but never recycled: another thread's trim can hit a bitmap the drawing thread holds for the current
frame. `BitmapBudget` never calls a client back while holding its own lock — clients charge while
holding *their* locks, so a callback under both would invert the lock order.
**Add/remove page** edit the page list through the pure, tested `PageOps` (a new page
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

**Shapes, styles, partial eraser, layers.** The **shape tools** (Line/Arrow/Double arrow/Rectangle/Ellipse/Coordinate axis) turn a
one-finger drag into an ordinary constant-width pen stroke: `ShapeBuilder` (pure, tested) converts the
drag's start/end into a vertex list, previewed live and committed as one undoable stroke, so shapes
round-trip like any stroke. The **spline tool** is the one shape whose gesture spans several touches,
so it bypasses the single-drag path: `DrawingSurfaceView` accumulates `SplineNode`s (anchor + tangent
handle — a tap sets the anchor, the drag that follows sets the handle) and `SplineBuilder` (pure,
tested) flattens the chain into one vertex list by sampling a cubic Bézier per node pair, with C¹
continuity at every node. The curve previews through the same in-progress-stroke path as everything
else and commits on a double-tap, on `Enter` (routed from `MainActivity.dispatchKeyEvent`, so the
canvas never takes keyboard focus), or when the tool changes; `Escape` discards it. The **shape recogniser**
(`ShapeRecognizer`, pure and tested) is the reverse direction: on commit, with the user's
**Shape recognition** setting on and the pen active, a thinned freehand stroke is classified — closed
strokes by an ellipse radius-spread fit and then by corner count (coarser Douglas-Peucker budget, so
a hand-rounded corner doesn't read as two), open ones by chord straightness, an arrow's
short-barb-folded-back signature, and finally a short polyline. Every test is scaled to the stroke's
own bounding box, and an unmatched stroke returns `null` so handwriting commits untouched; a match is
rebuilt through `ShapeBuilder` and stored constant-width. **Snapping** (`Snapping`, pure and tested) is
applied at the input edge rather than in the geometry builders: with **Snap to grid** on, the shape
drag's start and end points are rounded onto the page background's ruling before they reach
`ShapeBuilder`, and with **Snap rotation** on, the *swept* angle (not the absolute one) of the rotate
handle is rounded to 15° before it reaches `SelectionOps.rotate` — snapping the swept angle means a
snapped drag returns exactly to the element's original orientation. A **drawing guide** (`DrawingGuide`, pure and tested) is the same idea taken further: a setsquare
(right triangle) or compass (circle) posed in page-local pt, whose `project` pulls any point within
`GRAB_PT` onto its nearest edge and leaves anything further away untouched. `DrawingSurfaceView`
owns the live pose, paints the overlay in `paint()` (it is chrome, not ink, so it stays out of
`InkCache`), and funnels *every* drawn vertex through `guided()` — freehand samples in `point()` and
shape-tool endpoints in `startStroke`/`extendStroke`, applied **after** the grid snap so the guide
wins. A finger that lands on the guide's *body* (the triangle's interior, the compass's hub) drives it on
its own pointer id, running alongside the drawing gesture rather than replacing it, which is what
lets the pen rule along a guide the other hand is holding; the edges are deliberately excluded from
the grab test so drawing against one never drags the instrument away with it. Nothing about a guide reaches the document. Which axes snap is a property of
the background style, so the pt spacings live once in `BackgroundGrid` and are shared by
`BackgroundRenderer`, `PdfBackgroundPainter` and `Snapping`. The **setsquare/compass guides**
(`DrawingGuide`, pure and tested) are a third constraint at that same input edge: the surface holds
one live pose pinned to a page, and every drawn vertex — freehand via `point()` and shape-tool
endpoints alike — passes through `guided()`, which projects the point onto the guide's nearest edge
when it is within `DrawingGuide.GRAB_PT`. The guide is applied *after* grid snapping, so a placed
guide wins. It is drawn as a canvas overlay outside the ink cache (it is not page content) and is
manipulated by a finger on its own pointer id, deliberately running alongside the drawing gesture
rather than instead of it, so a hand can hold the instrument while the pen rules along it. Nothing
about a guide reaches the document — only the resulting stroke does. A **line style** (`plain`/`dash`/`dashdot`/`dot`) and a **fill** alpha ride
on the stroke the tool draws next; `StrokePainter` paints a dashed/dotted style as a single
constant-width dashed path and floods a fill under the outline, and `PdfVectorPainter` mirrors both for
export (a `setLineDashPattern` stroke and a `fill()` polygon). The **partial eraser** (`StrokeEraser`,
pure, tested) rubs out only the touched part of a stroke and splits it into the surviving pieces (each
inheriting the original's colour/style/fill), alongside the original whole-stroke delete
(`StrokeHitTester`). It hit-tests **segments**, not just vertices, and cuts where a segment crosses the
tip's disc — so a sparse shape stroke (a two-point line, a five-point rectangle) rubs out mid-shaft
just like densely-sampled freehand ink. `PageEraser` (pure, tested) is the page-level driver both modes go through: it
walks the page's layers, **skips hidden ones** (you only rub out ink you can see) and returns `null`
when nothing was touched, so the surface skips the document rebuild and the undo snapshot. The mode is
a view flag on the surface (`eraserMode`), set by which member of the rail's eraser slot is picked —
`EditorTool.ERASER` vs `ERASER_WHOLE`, so the choice is a tool, not a separate menu. The tip size has
no scheme of its own: `DrawingSurfaceView.eraserRadiusPt` derives it from the pen's `baseWidthPt` via
`eraserRadiusPt()` (`ERASER_RADIUS_FACTOR`, floored at `ERASER_RADIUS_MIN_PT`), in **document pt** so
it is zoom-invariant, matching the desktop. **Layer management** (`LayerOps`, pure, tested) adds/
deletes/renames/reorders/merges-down layers and moves a selection between them (all undoable;
`mergeDown` appends the upper layer's elements after the lower one's so z-order survives, keeps the
lower layer's name, and drops the emptied upper layer), while the *active*
layer (where new ink lands) and per-layer *visibility* are view-only editor state on the surface —
visibility just skips a layer in `PageRenderer.drawElements`, so it never touches the file. The UI for
all four lives in the rail's **Tool** (shapes, eraser mode), **Style** (line style / fill), and
**Layers** pop-ups (`SideToolbar`). Fill is a switch plus a continuous alpha `Slider`
(`ToolbarStylePopup.FillControls`) rather than preset levels; its on/off state and alpha persist as
`AppSettings.fillEnabled` / `fillAlpha`, and `AppSettings.currentFill` derives the surface's fill
from that pair (`null` when off) instead of the screen holding separate session state.

**Authoring non-stroke elements.** With the **Text**, **Image**, or **LaTeX** tool active, the
surface is in a *placement* mode (`placeKind`): a one-finger tap (not a drag) raises `onPlace` with
the page-local point, which `EditorOverlays` turns into a keyboard dialog (text/LaTeX) or, for images,
an `onPickImage` callback up to `MainActivity`'s SAF picker. The chosen content is inserted via
`insertText` / `insertTex` / `insertImage` — each a single undoable edit appended to the page's top
layer. Tapping an existing text box reopens it for editing (clearing the content deletes it);
matched by element identity. The view keeps the loaded document intact and only appends/edits, so
every page, layer, and element round-trips through save.

**Audio-annotated strokes (`audio/`).** Xournal++ can record while you write and then replay from
any stroke: the stroke carries `fn` (a `.wav` file name) and `ts` (how far into that recording it was
started). We implement both ends of that.

*Recording.* The **Audio** rail slot toggles capture. `AudioSession.startRecording` names the file
with the desktop's local-time `yyyy-MM-ddTHH-mm-ss.wav` convention and hands it to `AudioRecorder`,
which pumps `AudioRecord` (44.1 kHz mono 16-bit PCM) into a `WavWriter` on its own thread — Android
has no WAV encoder, so `WavWriter` frames the RIFF header itself and patches its two length fields on
close. `RECORD_AUDIO` is requested the first time Record is pressed; everything else in the app works
without it.

The surface's `audioStamp` hook is read **inside `appendStroke`**, which is the single funnel every
committed stroke passes through — so freehand, shapes and splines are all stamped, and the audio
machinery stays out of the drawing hot path. The `ts` it stamps comes from the *bytes written so far*
(`WavWriter.durationMs`), not wall time, so a stroke's offset points at the sample it was really
drawn over even if the capture thread stalls.

*Replay.* The **Play object** tool sets `audioPlayMode`, which short-circuits `beginPointer` before
the gesture classifier — it is a pure query that never edits the document, so it earns no
`GestureIntent`. A tap picks the topmost stroke (`SelectionTester.pickTopmost`), reads its `AudioRef`,
and `AudioPlayer` seeks a `MediaPlayer` to that offset. A tap that misses, or lands on a stroke with
no recording, says so rather than failing silently.

*Sidecars.* A `.xopp` never carries its audio, and SAF grants access to the single document the user
picked — not to its folder — so we can't write a sibling from a `CreateDocument` URI alone. Instead
recordings are captured into an app-private directory (always available, no permission needed), and
the user nominates an **audio folder** once from the Audio pop-up; that persisted `OpenDocumentTree`
grant is the sidecars' home on disk. Opening a document pulls the files it references in; saving (and
stopping a recording) pushes them back out. Without a nominated folder audio still records and plays
for the session — only the hand-off to and from the desktop is missing, and the app says so. `fn` is
reduced to a bare file name before use, so a hand-edited path in a document can't escape that folder.

**Vertical space (`render/VerticalSpaceOps.kt`).** The **Vertical space** tool
(`EditorTool.VERTICAL_SPACE` → the surface's `verticalSpaceMode`, classified as
`GestureIntent.VERTICAL_SPACE`) reflows a page: pointer-down latches the grabbed page and the
page-local Y of the grab line, and each move frame re-applies `VerticalSpaceOps.shiftBelow` to the
**gesture-start snapshot** (the same recompute-from-the-start discipline as a selection move, so a
live drag never drifts or compounds). An element moves when its `ElementBounds.of(...).top` is at or
below the line — so the line never tears an element in half — and every layer of the page moves
together, matching desktop. Dragging up is clamped by `clampShift` so content can close a gap but
never crosses above the line it was grabbed at; a drag that can't move anything returns the same page
list, which keeps `finishGesture` from recording an empty undo step. The whole drag is one undo step,
and because it only rewrites coordinates the result round-trips through save unchanged.

**Selecting objects (`render/`).** The **Select** tool (`EditorTool.SELECT`, mapped to the surface's
`selectMode`) mirrors desktop Xournal++'s object selection. A one-finger **drag** draws a rubber-band
marquee and selects every element **wholly enclosed** by it (desktop's rectangle-select semantics); a
one-finger **tap** picks the single topmost element under the point. Selection is **per page** —
anchored to the page the gesture started on — and elements are addressed by position, not identity, via
`ElementRef(layerIndex, elementIndex)`: a move rewrites the element objects but never reorders them, so
the refs stay valid across a live drag. The picking is pure and JVM-tested — **`ElementBounds` is the
single owner of "what rectangle does this element occupy"**: `ElementBounds.of` gives each element's pt
bounding box (strokes grown by half-width, images/teximages are their box, text a rough content-extent
metric) and `ElementBounds.TAP_PAD` is the one hit-test margin. Every consumer routes through it —
`SelectionTester` (picking), `PageRenderer` (viewport cull), `VerticalSpaceOps` (the grab line) and
`ElementEdits.hitsText` (tap-to-edit) — so selection handles, culling and vertical-space insertion agree
by construction rather than by coincidence; nothing re-derives a box locally. `SelectionTester` does
rect-containment / topmost
tap / union-bounds, and `SelectionOps` translates or deletes the addressed elements on a page list
(returning a new list; immutable pages/layers share structure so a snapshot stays cheap). Dragging inside
the selection outline translates the elements live (recomputing from the gesture-start document each
frame so there's no drift) and commits as **one undoable edit**; a floating **Delete / Deselect** bar
(`EditorOverlays.SelectionActionBar`, shown via `onSelectionChanged`) deletes (undoable) or clears the
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
not thread-safe) and `BackgroundRenderer` draws the rasterised page. The cache is keyed by page,
target-width bucket and (for tiles) grid cell, **LRU** under a heap-proportional byte budget (`PdfPageCache.budget`, a quarter
of the heap clamped to 24–192 MB — a page's cost varies ~64× between zoom levels, so counting pages
budgets nothing). Rasterisation never happens on the drawing frame: `request` returns whatever
resolution is already cached for that page (the nearest width, upscaled, or nothing) and queues the
exact size on a single worker thread, which fires `onPageReady` so the view redraws sharp. The view
also `prefetch`es one page either side of the viewport, so scrolling a long document meets a filled
cache. Evicted bitmaps are *not* recycled — the drawing thread may still hold one for the frame in
flight; the GC reclaims them. Past the whole-page raster ceiling `requestTiles` supplies
viewport-sized tiles rendered at the true on-screen scale (see the zoom paragraph above), so text
keeps sharpening as you zoom. A `.xopp` whose PDF isn't present falls back to a
plain sheet. **Import PDF** (`PdfImport`, invoked from `MainActivity`) copies the picked PDF into
app cache and builds pages from it (`PdfImport.pagesFor`) — one page per PDF page, sized from the PDF,
with the `filename`+`domain` on the first of them only and `pageno` thereafter (the desktop on-disk
convention). An `ImportPdfMode` chosen up front (the `ImportPdfDialog`) decides where they land:
`REPLACE` makes them the whole `Document` (`PdfImport.documentFor` → `load`), `APPEND` adds them after
the open document's pages as one undoable edit (`PageOps.appendPages` → `DrawingSurfaceView.appendPages`,
which also drops a lone untouched blank sheet so it isn't stranded in front of the PDF). Because the
`filename`/`domain` convention — and `XoppZip`'s single embedded `bg.pdf` — allow exactly **one** PDF
per document, `APPEND` onto a document that already has a PDF background does not add a second
reference: `MainActivity.appendMergedPdf` **merges** the two PDFs into one via `DocumentIo.merge`. `PdfMerger.join`
concatenates the current background PDF and the incoming one with PDFBox's `PDFMergerUtility` (source
pages imported verbatim, so they rasterise and re-export unchanged); the joined file is written to
`filesDir` — not the cache, so the link a plain `Save` records survives — under a name allocated by
that pane's joined-PDF `PdfStore`, so a merge never writes the file it is reading. The
new pages are sized from the incoming PDF with `PdfImport.pagesFor(reference = null, pageNoOffset =
<existing PDF page count>)`, and `DrawingSurfaceView.appendPdfPages` then re-points the document's one
reference at the joined file (`documentWithPdfReference`) **and** appends the pages in a **single
undoable edit** — the two halves must move together, since the appended `pageno` values index the
joined document. Existing pages keep their `pageno`, being at the front of the join. `Save`
(`ORIGINAL`) links the joined PDF by path; `Save As` (`ZIPPED`) embeds it as `bg.pdf` through the
usual `documentWithPdfDomain` rewrite, which is the easy case. **Export
PDF** (`PdfExporter`) flattens the document back out with **PDFBox** (`com.tom-roush:pdfbox-android`,
the one non-framework runtime dependency — see the note below): a `pdf`-backed page whose source PDF
is available (`PdfPageCache.source`, the cached import) is **imported verbatim so its original vector
content is preserved** (`PDDocument.importPage`), and the annotations are appended over it as a
**vector overlay** (`PdfVectorPainter`, an `APPEND`-mode content stream); every other page becomes a
fresh sheet whose background ruling is drawn as vectors (`PdfBackgroundPainter`) with the same
overlay. `PdfVectorPainter` mirrors the on-screen `StrokePainter`/`ElementRenderer` geometry at scale
1 — the `.xopp` unit == the PDF unit (1/72") — flipping y into PDF's bottom-left space via
`PdfPageTransform`; pen strokes taper per segment, the highlighter is one constant-width translucent
path, `.xopp` text elements use the base-14 fonts (see **Fonts in generated PDFs** below), and images
embed losslessly. **Nothing is rasterised** except
user bitmap images (already raster), so a no-op import→export round-trips a PDF at ~its original size
and fidelity instead of bloating ~10× from a raster flatten. **Rotated source pages** (`/Rotate`
90/180/270) are handled: since the on-screen renderer already applies `/Rotate`, annotations are
authored in the page's *visual* space, so `PdfExporter` pre-multiplies the overlay content stream by
a `PdfOverlayMatrix` (a pure, unit-tested `cm` matrix — the inverse of the display rotation, with the
crop-box origin folded in) that maps visual coordinates into the page's unrotated content space; the
viewer's `/Rotate` then cancels back to the drawn position, so strokes, text, and images all land
correctly. For `/Rotate 0` the matrix is just the crop-origin shift.

**Fonts in generated PDFs.** The PDF **base-14** fonts (`PDType1Font.HELVETICA` and friends) only
encode WinAnsi, so `PdfVectorPainter` drops any codepoint outside `0x20..0xFF`. That is acceptable
for `.xopp` `<text>` elements — desktop Xournal++ owns their font description and the element is
preserved in the file regardless — but **not** for **text import**, where the source is arbitrary
UTF-8 and the glyphs only exist in the generated PDF. So the text-import path draws with fonts
**bundled as app assets** and embedded per document by `PdfFonts`
(`PDType0Font.load(doc, stream, subset = true)`): PDFBox subsets them into the output, so a CJK or
Cyrillic import renders identically everywhere without bloating the file with a whole face.

The bundled pair is **DejaVu Sans** (proportional) and **DejaVu Sans Mono** (monospace), chosen for
broad Unicode coverage at a modest ~1.1 MB and a permissive licence — Bitstream Vera + Arev, with the
DejaVu changes in the public domain, compatible with the Apache-2.0/OFL-only rule that already ruled
out iText. They live in `app/src/main/assets/fonts/` with the full licence text beside them as
`LICENSE.txt`. Codepoints even DejaVu lacks are **substituted, never fatal**: `GlyphSanitizer` (pure,
unit-tested; encodability injected as a predicate) maps each unencodable codepoint to U+FFFD — or `?`,
or a space — memoising per codepoint, and iterates by codepoint so an astral character becomes one
substitute rather than two surrogate halves. `PdfFonts.Embedded.measurer` is also what feeds
`TextPaginator`'s injected measurement, so wrapping is measured against the exact font that draws.

**Generating the text-import PDF.** `TextPdfGenerator` turns a plain-text file into the PDF a text
import is opened against. It owns only the authoring — layout is entirely `TextPaginator`'s — and
emits **real, selectable text**: a white sheet per page, then one `beginText`/`setFont`/
`newLineAtOffset`/`showText` per laid-out line at `heightPt - baselineFromTop(i)` (PDF's origin is
bottom-left, the paginator's is top-left). Nothing is rasterised and no OCR is involved, so
`PdfTextExtractor` recovers the original characters and text selection works on an imported `.txt`
exactly as on a born-digital PDF. The font is **injected** as a `(PDDocument) -> PdfFonts.Embedded`
loader, keeping the generator free of `AssetManager` and unit-testable on the JVM; the app passes the
monospace face, the sensible default for logs and source. An empty file still yields one blank sheet
to annotate.

**Wiring a text file into the open path (`io/TextImport.kt`).** A `.xopp` cannot represent "a text
file" — the only thing that round-trips is a PDF background — so `DocumentIo.read()` short-circuits
`FileKind.TEXT` the same way it does `FileKind.PDF`: typeset the bytes, return
`LoadedFile.Pdf(generated = true)`, and every path downstream (background rasterisation,
`PdfImport.documentFor`, text selection, saving) runs unchanged with no text-specific branch. The
generator is injected into `DocumentIo` (it needs the bundled fonts, and so an `AssetManager`), which
keeps the rest of the class Android-free.

**Markdown routes on the name, not the bytes.** A `.md` file is printable UTF-8 like any other text,
so sniffing cannot separate the two: `FileKind.of` still returns `TEXT` for markdown, and the
markdown verdict is a **second, name-level** one — `FileKind.isMarkdownName(name)` matches a `.md` /
`.markdown` suffix (case-insensitively) on the *display* name SAF hands `DocumentIo.read()`. That is
the single place in the open path an extension is consulted, and it is consulted only to pick a
**flavour**, never a format. `TextImport.pdfFor` turns the name into a `render/TextFlavor`
(`PLAIN` · `MARKDOWN`) and rides it through `TextPdfGenerator.generate(…, flavor)` rather than
forking a parallel import class — everything downstream of the generated PDF is identical, so a
second path would buy nothing. The `MARKDOWN` branch in the generator is the seam the markdown
parser and block layout land in; until they exist it typesets the source verbatim, which is a
correct if plain result. Each flavour carries its own `cachePrefix` (`text:` / `markdown:`) so the
same bytes opened as `notes.txt` and as `notes.md` cannot collide in `PdfStore`.

**Parsing markdown (`render/markdown/`).** Structure and geometry are split the same way plain text
splits them: `MarkdownParser` turns source into a tree of `MarkdownBlock` and stops there — no
measurement, no wrapping, no pages — exactly as `TextPaginator` is pure geometry with no knowledge of
markup. The parser is dependency-free by policy (no CommonMark library): it is a **line-based
recursive descent** over normalised lines (CRLF→LF, tabs expanded once, so indentation is
countable), with the per-line "what does this start?" rules factored into `MarkdownLine` so each can
be tested against a single string.

Two decisions are load-bearing:

- **Nesting is the tree, not a depth field.** `Quote` holds its child blocks and `ListItem` holds
  its child blocks, so containers *recurse*: a quote's stripped content and a list item's dedented
  content are each re-parsed as a document of their own. A list item containing a code block, or a
  quote containing a list, therefore needs no special case, and no `depth` integer can fall out of
  sync with the structure — layout counts depth as it descends.
- **Inline markup stays raw.** A `Paragraph` or `Heading` carries its source text with `**bold**`
  and `[a](b)` intact; decoding spans into styled runs is a separate pass, which keeps block
  structure testable on its own and stops one parser from doing two jobs.

The dialect is the common core of CommonMark — ATX and setext headings, paragraphs with lazy
continuation, fenced and indented code, ordered and unordered lists with nesting, block quotes,
thematic breaks. Reference links, tables, HTML blocks and footnotes are out of scope and survive
verbatim inside a paragraph rather than being mangled. `MarkdownParserTest` covers each block type
plus the cases a hand-written line parser gets wrong: lazy continuation, loose lists, unclosed
fences, `* * *` beating a bullet marker, CRLF and tabs.

**Inline spans → styled runs.** The deferred second pass is `MarkdownInlineParser`: raw inline
source in, a flat list of `StyledRun` (`text` + `bold`/`italic`/`code`) out — still pure, still
unmeasured. Runs are **flat rather than a tree** because wrapping only ever asks "which font do I
measure the next word in?", so nested `**bold *and* italic**` is just adjacent runs with different
flags. It runs in two small stages: `InlineScanner` walks the source once and resolves everything
decidable locally (backslash escapes, code spans including the double-backtick form and the
symmetric pad-space rule, and link/image labels), emitting `*`/`_` as unresolved delimiter runs
tagged with CommonMark's flanking rules; `InlineEmphasis` then pairs those runs with a
delimiter-stack walk (including the rule of three), stamping styles onto the tokens between each
match. Nesting needs no recursion because a token can be stamped twice, and any delimiter that never
finds a partner prints literally — which is what keeps `2 * 3 * 4` and `snake_case_name` intact.

Two decisions here:

- **Link URLs are dropped; the label renders.** `[label](url)` becomes just `label` (and
  `![alt](url)` just `alt`). The output is a printed PDF page where a URL is neither clickable nor
  wanted mid-sentence.
- **A label is frozen into finished runs before it is spliced in.** Parsing a label through the full
  pipeline (rather than splicing its raw tokens) is what stops a leftover delimiter inside a label
  from pairing with one outside it.

**Styled runs → laid-out lines.** `StyledWrapper` is the markdown counterpart of `TextPaginator`'s
wrapping half: it takes styled runs plus a `(RunStyle, String) -> Float` measurer — one metric per
face (`REGULAR` · `BOLD` · `ITALIC` · `BOLD_ITALIC` · `CODE`, with `code` beating emphasis) — and
returns lines of `RunFragment`s, each a stretch of one face with an x offset and width. Baselines
stay pagination's job, so a fragment carries no Y.

The break rules are deliberately the plain path's: greedy word fill, and a mid-word hard break only
when a word can't fit a line alone. The shared character-level pieces (tab expansion, hard break)
live in `TextWrapping`, which both wrappers call, rather than being copied. What genuinely differs is
that a *word* can span runs — `**bold**tail` is one unbreakable word in two faces — so words are
tokenized as (style, text) segment lists and measured segment by segment, and adjacent same-face text
merges into one fragment, re-measured rather than summed so kerning stays honest. Inline whitespace
collapses to a single separator (markdown's own rule; verbatim spacing belongs to code blocks, which
are never inline-wrapped). Like the parsers it is pure, so `StyledWrapperTest` drives it with
synthetic metrics where bold is deliberately wider than regular and checks that a face change alone
moves a break.

Two consequences fall out of the generated PDF living only in the cache:

- **It is cached by content, not copied.** `PdfStore.cached(key) { … }` keys the generated file on a
  SHA-256 of the source text plus the file's display name (the staged copy's own name is per-open
  scratch and would never hit), recording key → file name in an `index.tsv` sidecar the sweep skips.
  Reopening the same file while the entry survives reuses the PDF instead of typesetting it again.
  `MainActivity.adoptPdf(inStore = true)` then takes the store file **in place** rather than copying
  it, so the cached file *is* the tab's `pdfPath` — which is what keeps liveness pruning from
  sweeping it out from under its own cache entry. A cached entry deliberately **outlives its tab** —
  `prune` keeps every file the index still names — so the cache is bounded by a **byte budget**
  instead: once the folder exceeds `StorageLimits.pdfCacheBytes` (Settings → Storage), `prune`
  evicts the oldest non-live files until it fits, and drops the index entries whose file it deleted,
  so an evicted entry simply regenerates on the next open.
- **It must be saved `ZIPPED`, not `ORIGINAL`.** There is no stable on-disk source to link: the
  cache path would be swept and the document would reopen blank. So the text branch makes the sticky
  save format `ZIPPED`, and `encode` embeds the bytes through the existing `domain="attach"` path.
  `TextImportRoundTripTest` guards the whole journey — open text → annotate → save → reopen — and
  `TextImportTest` the caching contract.
- **It is bounded by a size cap.** Typesetting holds the whole text *and* its whole laid-out page
  list in memory, so an unbounded import of a several-hundred-megabyte log would stall the app and
  fill the cache. `TextImport.pdfFor` therefore checks the staged file's **length before reading a
  byte** against `StorageLimits.textImportBytes` and throws `TextTooLargeException` when it is over;
  `MainActivity`'s existing "Open failed: …" toast shows the message, which names both sizes and
  points at Settings → Storage. Both limits are pushed into `DocumentIo.limits` by
  `MainActivity.applyStorageLimits` on load and on every settings edit, since `DocumentIo` outlives
  any one `AppSettings` value.

Those JVM tests need one build-level accommodation: PDFBox reads its **CMap data** (`Identity-H`,
needed by every `PDType0Font`) out of the AAR's `assets/` through Android's `AssetManager`, which
unit tests don't have. `app/build.gradle.kts` therefore extracts the AAR assets into the unit-test
resources (`extractPdfboxAssets`), where PDFBox's classpath fallback finds them, and sets
`unitTests.isReturnDefaultValues` so FontBox's `android.util.Log` calls no-op instead of throwing.

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
rail's head is **one slot per tool group** (`ToolGroups.kt`): `TOOL_GROUPS` partitions every
`EditorTool` into named groups (draw · eraser · line · shape · pan · select · insert), and
`ToolGroupButton` renders each as a single button faced with that group's current tool — tap to
activate it, long-press for a `DropdownMenu` over the group's members. A pick writes
`AppSettings.toolGroupSelections` (a `groupId → EditorTool` map, encoded as `group:TOOL` pairs in
one pref, so slots survive a restart) and activates the tool in the same gesture. `selected()` and
`decodeToolGroupSelections()` both drop non-member entries, so a stale pref degrades to the group's
first tool rather than facing a slot at a tool that has since moved. `startingTool()` resolves the
opening tool as `defaultTool`'s **group selection**, so the rail's face and the live tool agree on
launch. **Which positions the rail shows, and in what order**, is data too (`RailItems.kt`):
`RAIL_ITEMS` names every position — the seven tool groups plus the Colour/Size/Style/Layers/Zoom/
Background/Pages panels — and `SideToolbar` renders `visibleRailItems(railOrder, railHidden)`,
dispatching each id to its group button or panel. The Toolbar settings section edits those two prefs
(`AppSettings.railOrder`, a comma-separated id list, and `railHidden`, the same encoding for the
switched-off ids); a row is reordered by **long-press drag** (`detectDragGesturesAfterLongPress`),
which steps `moveRailItem()` one place each time the finger crosses a row height, so the list
reshuffles live. The in-flight order is held in the section's own `draggedOrder` state because
several steps can land inside one frame, before `onChange` has recomposed `settings`. `orderedRailItems()` **appends** any id the saved order omits in factory order, so
a position added in a later release still appears for an existing install, and `decodeRailIds()`
drops ids that no longer exist. The tools are UI-level
`EditorTool`s (Hand is view-only pan and Text/Image/LaTeX are placement modes, none a document tool,
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
from the ☰ menu swaps in `SettingsScreen`, which is an **index of sections** (`SettingsSection` —
Stylus, Editor, Toolbar, Palette, Navigation, Appearance, Storage): each row opens that section as its own page, with back returning to the
index and back from the index leaving settings. Section bodies live in `SettingsSections.kt`. The fixed pen palette (`PEN_COLORS`, `PEN_WIDTH_LABELS`)
lives beside its pop-up (`ToolbarColorPopup.kt` / `ToolbarSizePopup.kt`); the user-configurable pen widths and the editable custom colour are
persisted in `AppSettings`/`SettingsStore`, and the arbitrary-colour HSV/hex picker is in
`ColorPicker.kt`. Every place a colour is chosen — the pen's rail button, the text-box dialog, the
selection recolour menu — renders the **one** `ColorPaletteRows` component (`ColorPalette.kt`):
fixed swatches, the editable custom slot (long-press → `CustomColorEditor`) and the recents row,
reading and writing one `ColorPaletteState` over `AppSettings`. The swatches wrap (`FlowRow`) so the
custom slot survives a narrow dialog, and the HSV editor is hoisted *outside* the menu that opened it
so dismissing that menu doesn't take the dialog with it. `AppSettings` also remembers the pen you
left off with — `lastColor`/`lastWidth`, re-pushed onto a freshly created surface by `EditorScreen` —
and a `recentColors` MRU list (`withColorUsed`, capped at `MAX_RECENT_COLORS`, stored as a
comma-joined pref) shared by all three pickers; only the pen's own picks pass `asPen`, so colouring
text or a selection fills recents without changing what the pen draws with next launch. The **Select** tool adds a rail entry and a floating action bar; its
mechanics are in [Selecting objects](#selecting-objects-render) above.

## Relative PDF references {#relative-pdf-references}

A background reference is only useful if it still resolves on the machine that opens the file next.
An absolute Linux path means nothing on Android, and a `content://` URI means nothing anywhere but
the device that issued it — so **a path relative to the `.xopp` is the portable form, and the
default this app writes whenever it can.** Desktop Xournal++ has no `relative` domain: a relative
path is carried under `domain="absolute"` and resolved against the document's own folder
(`LoadHandler::getAbsoluteFilepath`), so what we write is exactly what desktop already reads.

The string logic lives in `io/PdfReference.kt` — pure and unit-tested (`PdfReferenceTest`), with no
Android types, so the same helpers serve both filesystem paths and SAF **document ids**
(`primary:Docs/notes.xopp`), which are `/`-joined paths behind a volume root.

**Reading** (`DocumentIo.resolvePdfBackground`, which now takes the source URI of the `.xopp` being
opened) handles four shapes, in order:

| Reference | Resolved as |
|---|---|
| `content://…` | opened directly (what this app records for a picked PDF) |
| `/abs/path.pdf` | opened as a file, if it exists on this device |
| `bg.pdf`, `scans/bg.pdf`, `../bg.pdf` | **relative** to the `.xopp`'s own folder |
| `domain="attach"` on a non-zip document | the `<name>.xopp.<filename>` sibling |

The last two need a folder, which `openSibling` derives from the source URI: a `file://` URI
relativises on the filesystem, a `content://` one on its SAF document id via
`DocumentsContract.buildDocumentUriUsingTree`/`buildDocumentUri`. Providers with **opaque** ids
(Downloads' `msf:1234`) have no path to relativise, so resolution returns null and the pages come up
blank with the existing "Background PDF not found" note — the reference itself is still written back
untouched on save, so nothing is silently dropped.

**Writing** (`DocumentIo.portableReference`, applied on every `ORIGINAL` save) rewrites the
reference to be relative to the save destination whenever the PDF sits in the same folder — matching
SAF document-id volumes, or two filesystem paths. It is a heuristic, not a preference: a resolvable
relative path is strictly better than a `content://` URI no desktop can read, and there is nothing
for the user to get wrong. Three cases are deliberately left alone: an **already relative**
reference (so a desktop-authored document round-trips byte-identically), an **attach** reference
(the ZIP path owns that), and anything that **won't relativise** (different folder, different
volume, opaque id) — those keep their absolute reference rather than becoming a broken relative one.

**Round-trip status.** The parse/serialize halves are locked in by fixtures in
`PdfBackgroundRoundTripTest` (a relative path under `domain="absolute"`, and an attach reference on
a non-zip document) and the derivation by `PdfReferenceTest`.

The **desktop check** was run against **Xournal++ 1.3.6** on Linux, headlessly, via its export CLI
(`xournalpp FILE.xopp -p out.pdf`, which renders the resolved background into the exported PDF — a
blank page means the reference didn't resolve). Results:

| Fixture | Desktop 1.3.6 |
|---|---|
| `domain="absolute"` + bare sibling filename (`bhm_prior.pdf`) | ✅ background renders |
| The multi-page shape this app writes — first page carries `filename`+`domain`, later pages only `pageno` | ✅ all 3 pages render the right PDF page, strokes on top |
| `domain="attach"` on a non-zip document, `<name>.xopp.bg.pdf` sibling | ✅ background renders |
| Unresolvable reference (`nope.pdf`) | ⚠️ desktop reports *"The background file … could not be found"* and aborts the export (exit 2) |

So the reference forms we read and write are exactly what desktop resolves, and the inheritance
shape (`filename` only on the first page) is accepted. **Still unverified:** whether desktop
*preserves* an unresolvable reference across a save — that needs an interactive save, and no display
server (Xvfb/Wayland) is available on this box to drive the GUI.

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
   A *double-click* of the same button is a separate, button-only gesture: `BarrelClickDetector`
   (pure, time-injected, `BarrelClickDetectorTest`) recognises two press edges within 350 ms from
   the hover/generic event stream — never while the tip is down, so it can't interrupt a stroke —
   and runs the configured `BarrelDoubleAction` (undo/redo on the surface; the tool and full-page
   toggles are handed to `EditorScreen` through `onBarrelDoubleClick`).
   The `RADIAL_PALETTE` action is the **sole owner** of what a barrel double-click does — the
   separate `PaletteInvocation` setting covers *touch* gestures only (`NONE` by default, plus
   pen-tip long press and two-finger tap), so the two settings can never contradict each other.
   `RADIAL_PALETTE` opens the pen-tip menu at the event's own `(x, y)` and the
   overlay then **owns every pointer**: `onTouchEvent` returns before `beginPointer` while it is up,
   which is what makes "the menu can never leave a stroke behind" structural rather than a rule to
   remember. Hover moves re-hit-test the highlight (a hover *exit* is not a cancel — it's the tip
   coming down) and a lift commits — but **the menu stays up after a pick**, so several settings can
   be chosen in one summoning. It closes only when the user clicks off it: a release past
   `RadialPaletteGeometry.dismissRadius` (`RadialHit.Outside` — the slack between the outer ring and
   that radius keeps an over-flick a selection) or in the centre dead zone, or a second barrel
   double-click, which commits *and* closes as the eyes-free way out. The
   picked `PaletteAction` leaves the surface via `onPaletteAction` and is run by `applyPaletteAction`
   (`ui/PaletteActions.kt`), which is deliberately the *only* mapping from action to edit so the
   palette and the toolbar can't drift into two meanings of the same command.
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

- `StrokeSmoother` — per-stroke streaming filter over the view-pixel samples, reset in
  `startStroke` and fed by `addSamples`. It exponentially smooths position (α 0.55) and, harder,
  pressure (α 0.3), then **decimates** samples that moved less than `minStepPx` with less than 0.02
  pressure change. A decimated sample still advances the filter, but the decimation distance is
  measured from the last **emitted** point, so a run of sub-threshold steps that adds up to a real
  move still lands a vertex. The newest sample of every batch is `force`d through so the drawn line
  always reaches the pen. `minStepPx` is **scale-aware** (`StrokePrecision.stepPxFor(pxPerPt)`,
  passed to `reset` per stroke): the *tighter* of a 1.6 view-px noise floor and a 0.8 pt
  document-space ceiling. A fixed pixel radius meant a view pixel bought more page at low zoom, so
  a 4-column overview silently discarded ~4× the real pen movement — the "corners and jumps" bug.
- `StrokeSimplifier` — Ramer–Douglas–Peucker pass run once in `commitCurrent` over the finished
  freehand points, dropping vertices within its tolerance of their neighbours' chord. Shape-tool
  output is exact geometry and is exempt. Fewer vertices = smaller `.xopp` and fewer `drawLine`
  calls per redraw. The tolerance is **scale-aware**: `toleranceFor(pxPerPt, precision)` divides
  `TOLERANCE_PX` (0.35 view px) by the page's real pixels-per-point, so the detail thrown away
  stays sub-pixel *on screen* at every magnification — a fixed page-point budget is ~3 view px at
  8 px/pt and facets curves into visible straight segments. `pxPerPt` must be `PageBox.scale`
  (fit-to-width × user zoom), **not** the user zoom alone: on a large tablet fit-to-width is
  already 2–4 px/pt, so keying off the zoom leaves the budget that many times too coarse at 100%
  and below — the faceting bug this replaced. Below 1 px/pt that division runs the other way, so
  the budget is clamped at `TOLERANCE_PT` (0.35 pt): zooming out only ever *tightens* the budget.
- `StrokePrecision` — the user-facing **Stroke precision** setting (Economy / Balanced / High /
  Maximum). Its `factor` multiplies *both* budgets — the simplifier tolerance and the smoother's
  `minStepPx` — so one control moves the whole fidelity-vs-size trade. Both budgets also depend on
  the page's `PageBox.scale`, so `DrawingSurfaceView` computes them per stroke (`startStroke` and
  `commitCurrent`) rather than pinning them when the setting changes.

Both are covered by `StrokeSmootherTest`. **Hover** (`ACTION_HOVER_MOVE` from a
stylus, via `onHoverEvent`) draws a preview ring where the tip will land. All of these are settings in
`AppSettings`, persisted by `SettingsStore` (SharedPreferences) and pushed live onto the surface by
`EditorScreen.applySettings`; the on-device `StylusInputTest` drives synthetic tool-typed
`MotionEvent`s to prove the wiring (eraser tip, barrel erase, barrel double-click, finger-draw gate, palm rejection, and
that the same page-space gesture keeps its detail at 100% and zoomed out).
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

**Selection — desktop parity (shipped).** The tool now covers rectangle **and** lasso select
(separate members of the rail's select slot — `EditorTool.SELECT` / `LASSO_SELECT` — which
`applyTool` turns into the surface's `selectMode`/`lassoMode` pair),
tap-pick, move (including **across pages**), on-canvas **resize** (uniform, corner handles) and
**rotate** (top knob), **cut / copy / paste / duplicate**, and **recolour / re-width**. Every
transform is a pure `SelectionOps` op (`scale`/`rotate`/`restyle`/`moveToPage`/`addToTopLayer`
alongside `translate`/`delete`) and lasso containment is `SelectionTester.inPolygon`, all
JVM-tested. **Rotate is stroke-only by the scope rule:** a stroke bakes rotation into its vertex
coordinates and round-trips, but text/images have no rotation attribute and axis-aligned boxes, so
`rotate` leaves them untouched and the view shows the rotate knob only for an all-stroke selection.
Non-uniform resize is likewise avoided (a text box's font size is a single scalar), so resize is a
uniform scale that keeps every element representable.
