# TODO

The live task list and journal for this project — **active work only**. Single source of
truth for what's in flight and what to build next (see `CLAUDE.md` → documentation map).

Add proposed work **unchecked**; remove dropped items with a one-line why. When a task is
fully finished (built, tested, documented), **move** its checked, dated entry to
`FINISHED.md` — completed work is archived there, not kept here, so this list stays short.

## Active

> **Scope rule:** we only build features the `.xopp` format can represent on disk. If a
> capability can't round-trip through the file (e.g. tilt/orientation, which the format
> doesn't store), it's out of scope — don't add it. See `CLAUDE.md` → "What this project is".

**Selection — remaining desktop parity (see `docs/architecture.md` → "Stylus & selection roadmap"):**

- [ ] **Resize** and **rotate** handles on the selection outline (`SelectionOps.resize`/`rotate`).
- [ ] **Cut / copy / paste / duplicate** of a selection.
- [ ] **Lasso** (free-form) select in addition to the rectangle.
- [ ] Move a selection **across pages**; change selected strokes' **colour / width**.
