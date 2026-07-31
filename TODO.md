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

Tool-authoring polish: configurable pen sizes and colours. All shipped 2026-07-31 (text-tool
styling, configurable pen sizes, and the custom-colour slot) — see `FINISHED.md`.

### Navigation — right-hand-side scroll thumb for paging the document

- [ ] Add a **right-hand-side scroll thumb** that scrolls through the document's pages just like a
      PDF viewer's scrollbar. As the page stack scrolls, a draggable thumb tracks the current
      position down the right edge; **dragging the thumb** scrolls the document to the matching
      point (fast paging through a long document), and it should read naturally against the existing
      Pages navigator (`goToPage` / `onCurrentPageChanged`). A pure navigation affordance — no `.xopp`
      document state, so nothing to round-trip. Consider showing it only while scrolling/dragging
      (auto-hide) and a page-number bubble beside the thumb while dragging, à la PDF viewers.
