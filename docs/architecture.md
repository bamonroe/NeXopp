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

A `.xopp` file is a **gzip-compressed XML document**. The element/attribute mapping —
`<xournal> → <page> → <layer> → <stroke>/<text>/<image>` with their attributes (tool, color,
width, pressure list, coordinates, etc.) — is owned here.

- [ ] Document the concrete XML schema (elements, attributes, units, coordinate system,
      pressure encoding) — derive it from the reference clone and from real files (e.g. the
      `udiff.xopp` sample in the repo root), and from desktop Xournal++'s own writer.
- [ ] When the parser exists, add a **drift test** so this doc stays a faithful mirror of the
      code (per `CLAUDE.md`'s code-derived-fact rule).

## Data path

*(To be written once the stack is scaffolded.)* The core loop: `open → gunzip → parse XML →
in-memory document model → render on a stylus canvas → edit → serialize XML → gzip → save`.

- [ ] Define the in-memory document model (the native-Android analogue of the `Xpp*` types).
- [ ] Pin the drawing surface / stylus input approach (`MotionEvent` pressure, low-latency
      rendering).

## Repository layout

*(To be written once the Android project is scaffolded — every module and what it does.)*
