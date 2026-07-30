# Tools & build pipelines

Authoritative home for **external tooling** this project depends on — build pipelines, deploy
scripts, device/emulator harnesses, code generators, and anything else that isn't part of the
repo's own in-tree build. `CLAUDE.md` only *points* here; the specifics live in this file (or
in a dedicated `docs/<tool>.md` that this file links when the detail is large).

Add one entry per tool. Keep each entry to what a Claude instance needs to *use* it: where it
lives, how to invoke it, and the non-obvious gotchas.

## Index

| Tool / pipeline | What it's for | Detail |
|-----------------|---------------|--------|
| _[e.g. Android build]_ | _[build & test the Android app]_ | _[below, or `docs/android.md`]_ |

---

## [Tool name]

*Delete this example and add real tools as the project grows.*

- **Where it lives:** `[path or repo]`
- **How to run it:** `[command]`
- **Gotchas:** `[the things that bite — stale caches, required clean builds, auth, etc.]`
