# Architecture

Authoritative home for **how the system works internally**: the `.xopp` format mapping, the
read/write data path, the core components, the repository layout, and the load-bearing design
decisions. `CLAUDE.md` points here; the specifics live here.

> Status: early. The Android stack and code layout are not built yet — this doc currently
> holds the prior-art survey and the format notes that will ground the first implementation.
> Fill in the remaining `[…]` as the code lands, and record design decisions here so they
> aren't re-litigated.

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
- `type="pdf"`: `filename` (PDF path), `pageno` (0-based PDF page index); `domain` on the
  first pdf background of the doc.

**`<layer>`** — no attributes. Children in document order: any mix of `<stroke>`, `<text>`,
`<image>`, `<teximage>`. **Document order is z-order** and must be preserved on round-trip.

**`<stroke>`** — the core drawable. Attributes:
- `tool` ∈ `pen | highlighter | eraser` (highlighter is drawn semi-transparent; eraser
  strokes exist in the format but desktop rarely persists them).
- `color` — hex/named as above.
- `width` — **space-separated list of doubles (pt)**. The **first value is the nominal stroke
  width**; the remaining values (if present) are the **per-vertex pressure widths**. A single
  value means constant width. On read, if fewer widths than vertices, reuse the first for all.
- `capStyle` — `round` | `butt` | `square` (line cap; desktop attribute, default `round`).
- `ts`, `fn` — audio-recording timestamp / filename for pen-replay; `ts="0" fn=""` when
  unused. Preserve verbatim on round-trip; no rendering meaning for us.
- **Inner text**: a flat space-separated coordinate list `x0 y0 x1 y1 …` (pt). Vertex *i* is
  `(text[2i], text[2i+1])` and its width is `width[i+1]` (or `width[0]` if constant).

**`<text>`** — attributes `font` (family name), `size` (pt), `x`, `y` (pt, top-left anchor),
`color`. **Inner text** is the string, XML-escaped (`&amp; &lt; &gt;`).

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

## Stack — pinned 2026-07-30

Native Android, no cross-platform framework (the abandoned reference was Flutter; we go
native for stylus latency and platform fit).

- **Language:** Kotlin, targeting the modern Android SDK.
- **App chrome / UI:** **Jetpack Compose with Material 3** (Material You) for all app chrome —
  app bar, menus, dialogs, the tool palette. Satisfies the Material Design requirement in
  `TODO.md`.
- **Drawing surface:** a custom low-latency **`SurfaceView`** (not Compose `Canvas`) hosted in
  the Compose tree via `AndroidView`. Stylus input comes from raw **`MotionEvent`** with
  `getPressure()` / `getAxisValue(AXIS_PRESSURE)` and historical points
  (`getHistoricalX/Y/Pressure`) so fast strokes keep their samples. This is the load-bearing
  choice for "round-trip safety" — we capture pressure at the same fidelity the format stores.
- **`.xopp` I/O — no third-party format libraries.** Gzip via the JDK's built-in
  `java.util.zip.GZIPInputStream` / `GZIPOutputStream`; XML via Android's built-in streaming
  `XmlPullParser` (read) and `XmlSerializer` (write). Streaming keeps large documents off the
  heap and gives us exact control over attribute preservation (a fidelity requirement above).
- **File access:** the Storage Access Framework (`ACTION_OPEN_DOCUMENT` /
  `ACTION_CREATE_DOCUMENT`) so a `.xopp` opens/saves in place on the device — the file on disk
  is the only source of truth (per `CLAUDE.md` non-goals: no cloud, no custom format).
- **Build:** Gradle (Kotlin DSL) inside a **Docker** container (Podman fallback) per
  `CLAUDE.md`. Pipeline details live in `docs/tools.md`; build/run for a human lives in
  `README.md`.

## Data path

The core loop:

```
open (SAF Uri) → GZIPInputStream → XmlPullParser → Document model
   → render on SurfaceView (Material 3 chrome around it)
   → stylus edits mutate the model → XmlSerializer → GZIPOutputStream → save (SAF Uri)
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
- `Layer` — ordered `List<Element>` (z-order).
- `Element` — sealed type: `Stroke`, `Text`, `Image`, `TexImage`.
  - `Stroke` — `tool`, `color`, `capStyle`, `List<Point>` (each `x, y, width`), plus
    preserved raw attrs (`ts`, `fn`, unknowns).
  - `Text` — `font`, `size`, `x`, `y`, `color`, `content`.
  - `Image` — bbox `left/top/right/bottom`, decoded bytes.
  - `TexImage` — bbox, `latex`, `color`.

Colors are stored as Android `0xAARRGGBB` ints; the I/O layer converts to/from the on-disk
`#RRGGBBAA`. Coordinates are stored in pt (document space); the view applies a pan/zoom
transform to screen space.

- [ ] Firm up the model once code lands; keep this list in sync with the Kotlin types.

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
    render/
      DrawingSurfaceView.kt  # low-latency stylus canvas (MotionEvent pressure)
      PageStacker.kt         # lays pages out top-to-bottom, fit to width (pure geometry)
      BackgroundGrid.kt      # ruling line/dot offsets in pt (pure geometry)
      BackgroundRenderer.kt  # paints a page background (plain/lined/ruled/graph/dotted)
      ElementRenderer.kt     # draws text boxes, images, and teximage placeholders
      TextBlock.kt           # text line-split + baseline geometry (pure)
      StrokeHitTester.kt     # eraser point-to-stroke hit geometry (pure)
    ui/                      # Compose Material 3
      EditorScreen.kt, ToolPalette.kt, PenSettings.kt
      theme/                 # XoppTheme (Material You), Color
  src/test/java/com/xopp/android/format/                   # JVM unit tests for the format layer
  src/test/java/com/xopp/android/render/                   # JVM unit tests for layout/grid geometry
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
tested `StrokeHitTester`); **two fingers scroll** the stack. Strokes are drawn by the view; text boxes, images, and LaTeX images are drawn by
`ElementRenderer` (text baseline geometry lives in the pure, tested `TextBlock`; image bytes are
decoded once and cached by element identity). A `<teximage>` carries only its LaTeX source in the
model, so it renders as a best-effort placeholder — a faint box with the source text — until a
real LaTeX renderer lands. The view keeps the loaded document intact and only appends, so every
page, layer, and element round-trips through save. Editing the non-stroke elements, plus pan/zoom,
are still to come (`TODO.md`).
