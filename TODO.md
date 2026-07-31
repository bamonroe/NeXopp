# TODO

The live task list and journal for this project — **active work only**. Single source of
truth for what's in flight and what to build next (see `CLAUDE.md` → documentation map).

Add proposed work **unchecked**; remove dropped items with a one-line why. When a task is
fully finished (built, tested, documented), **move** its checked, dated entry to
`FINISHED.md` — completed work is archived there, not kept here, so this list stays short.

## Active

**Stylus input — remaining (design in `docs/architecture.md` → "Stylus & selection roadmap"):**

- [ ] **Tilt / orientation → width** (calligraphic pen). Capture is wired, but the `.xopp` format
      stores no tilt, so this needs a render-time calligraphic mode; deferred as its own feature
      rather than baked speculatively into width. The pressure-curve, hover, palm-rejection, eraser-
      tip, and barrel-button items shipped 2026-07-31 (see `FINISHED.md`).

**Selection — remaining desktop parity (see the same doc section):**

- [ ] **Resize** and **rotate** handles on the selection outline (`SelectionOps.resize`/`rotate`).
- [ ] **Cut / copy / paste / duplicate** of a selection.
- [ ] **Lasso** (free-form) select in addition to the rectangle.
- [ ] Move a selection **across pages**; change selected strokes' **colour / width**.
