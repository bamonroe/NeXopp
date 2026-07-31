# Xopp — a stylus-first Xournal++ editor for Android

**Xopp** opens, edits, and saves [Xournal++](https://github.com/xournalpp/xournalpp) `.xopp`
files on Android. Draw and handwrite with a pen/stylus on a tablet or phone, then save back to
the **same `.xopp` format** so the file round-trips cleanly to and from desktop Xournal++ on
Linux. The guiding principle is **format fidelity and round-trip safety**: a file edited on
Android reopens correctly on the desktop, and vice versa.

- What the project is and how to work in it: [`CLAUDE.md`](CLAUDE.md).
- How it works internally (the `.xopp` schema, data path, model): [`docs/architecture.md`](docs/architecture.md).
- Build/emulator tooling: [`docs/tools.md`](docs/tools.md).
- What's done and what's next: [`TODO.md`](TODO.md).

> Status: early. The `.xopp` read/write core and its tests are in place; the Android editor is
> a working scaffold (Material 3 chrome + a stylus drawing surface). See `TODO.md`.

## Requirements

Everything builds through the **shared Android toolchain in `/data/android`** (a baked
`android-builder:local` container), so the only host requirement is Docker. You do **not** need
a local JDK, Android SDK, or Gradle. To run the app on a virtual device you additionally need a
KVM-capable host for that directory's headless emulator (see [`docs/tools.md`](docs/tools.md)).

## Build & test

The one command you need:

```sh
scripts/build.sh
```

This runs the full check loop through the shared toolchain container — **unit tests + a debug
APK**. The SDK image is already baked; the first run only downloads Gradle and dependencies
into a per-project `.gradle-cache/`, so later runs are fast.

Common variants:

```sh
scripts/build.sh testDebugUnitTest     # JVM unit tests only (no device needed)
scripts/build.sh clean assembleDebug   # clean build of the debug APK
scripts/build.sh <any gradle tasks>    # arbitrary Gradle tasks in the container
```

Outputs:

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Test report: `app/build/reports/tests/testDebugUnitTest/index.html`

The unit tests cover the `.xopp` round-trip: the colour codec, every element type, XML
escaping, model reserialization, and a gzip round-trip. If a real desktop-generated
`udiff.xopp` is present at the repo root, an extra test round-trips it end to end (it
self-skips when absent).

## Run on a device / emulator

Install the debug APK on a connected device or a running emulator:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.xopp.android/.MainActivity
```

Emulator setup (AVD creation, headless launch, KVM notes) lives in
[`docs/tools.md`](docs/tools.md).

## Using the app

- **Open** — the folder/open action in the top bar launches the system file picker; choose a
  `.xopp` file. It's read in place via the Storage Access Framework. Every page is shown, one
  above the next, each drawn with its own background ruling (plain, lined, ruled, graph, or
  dotted) and all of its layers — including strokes, text boxes, and images. LaTeX images show
  their source in a placeholder box until math rendering is added.
- **Draw** — pick **Pen** or **Highlighter** from the tool palette and draw with **one finger or
  the stylus**; pen pressure sets stroke width. Choose a **colour** (swatches) and a base **width**
  (S / M / L) from the settings row above the palette. New strokes land on the top layer of
  whichever page you draw on.
- **Erase** — pick **Eraser** and drag over strokes to delete them; each stroke the eraser
  touches is removed whole.
- **Scroll** — drag with **two fingers** to move up and down the page stack.
- **Save** — the save action writes the whole document back out as a `.xopp` file (gzip + XML),
  preserving every page, layer, background, and element — including text, images, and LaTeX
  images that aren't edited on-device yet.

The file on disk is the only source of truth — there's no cloud, account, or custom format.

## Project layout

The authoritative layout lives in [`docs/architecture.md`](docs/architecture.md). In short:

```
app/src/main/java/com/xopp/android/
  format/      # .xopp read/write: model, colour codec, gzip, dependency-free XML layer
  render/      # DrawingSurfaceView — low-latency stylus canvas
  ui/          # Compose Material 3 editor screen, tool palette, theme
  MainActivity.kt
app/src/test/  # JVM unit tests for the format layer
Dockerfile, compose.yaml, scripts/build.sh   # containerized build
```
