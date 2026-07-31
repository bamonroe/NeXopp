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

- [ ] Add a small peninsula/outsert to the sidebar thumb so it is easier to grab.
- [ ] Add a settings option for the default tool.
- [ ] Add momentum scrolling so fast swipes continue moving pages after finger lift.
- [ ] Add a settings option to configure scrolling momentum strength.
- [ ] Add hand-tool double-tap page navigation: center double-tap toggles full-page view
      by hiding/showing the sidebar and top bar, left-edge double-tap goes to the previous
      page, and right-edge double-tap goes to the next page.
- [ ] Improve pen tip size controls: add minus and plus buttons beside the slider for
      small increments, and add a text input for entering an exact point size.
