# `.xopp` format fixtures

Small desktop-format `.xopp` files (gzip-compressed XML) consumed by
`FormatDriftTest`. Each was **validated to load in desktop Xournal++ 1.3.5**
(`xournalpp <file> --create-pdf=…` exits 0 and renders) before being committed,
so they are faithful to the on-disk format the app must round-trip — not
synthetic XML the parser happens to accept.

Inspect any fixture with `zcat <file>.xopp`. Together they cover the schema
surface documented in `docs/architecture.md`:

| Fixture              | Covers                                                        |
|----------------------|---------------------------------------------------------------|
| `plain.xopp`         | pressure (per-vertex width) + uniform-width pen, highlighter alpha |
| `backgrounds.xopp`   | one page per background style: plain, lined, ruled, graph, dotted (multi-page) |
| `layers.xopp`        | a single page with three z-ordered layers                     |
| `text-image.xopp`    | `<text>` (XML entities), `<image>` (PNG), `<teximage>` (LaTeX) |

To add a fixture: author the XML, gzip it here (`gzip -c src.xml > name.xopp`),
confirm it exports through desktop Xournal++, and add it to the `fixtures` list
in `FormatDriftTest`.
