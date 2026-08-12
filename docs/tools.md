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
| Android build | Compile & package the app (APK/AAB) | [below](#android-build) |
| Android emulator | Run & test the app on a virtual device | [below](#android-emulator) |
| `rnote-cli` | Generate & validate `.rnote` test fixtures | [below](#rnote-cli) |

---

## Android build

The pipeline that compiles and packages the Android app. **All APKs on this box are built with
the shared Android toolchain in `/data/android`** — the single front door for building (and
running) Android apps without a JDK/SDK/Gradle on the host. Per `CLAUDE.md`, the build runs
inside a container; that container is the baked `android-builder:local` image maintained in
`/data/android`, not one owned by this repo. This repo only supplies its Gradle project + the
Gradle wrapper (pinned to Gradle 8.9); the toolchain supplies JDK 21 + the Android SDK
(`platforms;android-34/35`, `build-tools;34.0.0/35.0.0`).

- **Where it lives:** `/data/android/` — `build.sh` (the disposable-container build front door)
  and `Dockerfile.builder` (the baked `android-builder:local` image). That directory has its
  own `README.md`/`CLAUDE.md`; read them for the full contract. This repo's `scripts/build.sh`
  is a thin wrapper that calls `/data/android/build.sh` (override the location with
  `ANDROID_TOOLCHAIN`).
- **How to run it:**
  - `scripts/build.sh` — the default check loop: `testDebugUnitTest assembleDebug`.
  - `scripts/build.sh <tasks…>` — arbitrary Gradle tasks, e.g. `scripts/build.sh testDebugUnitTest`
    or `scripts/build.sh clean assembleDebug`.
  - Direct equivalent: `/data/android/build.sh /home/bam/git/personal/nexopp <tasks…>`.
- **Outputs:** debug APK at `app/build/outputs/apk/debug/app-debug.apk`; unit-test reports at
  `app/build/reports/tests/testDebugUnitTest/`.
- **Gotchas:**
  - The builder mounts the project's **parent** dir as `/workspace` and runs `./gradlew` in the
    project subdir, so sibling files resolve; it keeps a **per-project Gradle cache** at
    `.gradle-cache/` (git-ignored) and sets `HOME` there for a stable debug keystore.
  - Runs as your UID (`--user`) — build outputs are owned by you, not root. (Don't run the
    builder image as root against the mount, or `build/` becomes root-owned and later
    user-mode builds can't clean it.)
  - The image is already baked; only Gradle + dependencies download on first use into
    `.gradle-cache/`. No `local.properties` needed — the SDK is baked in.
  - The parent mount is what makes the optional real-file test resolve its repo-root sample; the
    rule is documented in [`architecture.md`](architecture.md#what-the-unit-tests-cover).

## Android emulator

Running the APK on a device/emulator also goes through `/data/android` — a headless Android 14
emulator you drive over `adb`, plus physical devices on the tailnet (see its `config.yaml`).
**This emulator is the standard way to test the app: don't stop at "it compiles."** Every
change with a runtime surface must be installed and exercised on the emulator, not just built.

- **Where it lives:** `/data/android/` — `docker-compose.yml` (the emulator container) and
  `.claude/skills/android-dev/scripts/emulator.sh` (the driver), alongside `adb-targets.sh`
  which lists every target across the two isolated adb worlds (host adb → physical devices,
  container adb → emulator). Full details in that directory's `README.md`; don't duplicate them here.
- **How to run it:** `emulator.sh` dispatches `status | up | boot-wait | down | install <apk> |
  launch <pkg> | screenshot [png] | ui | logcat | shell | adb` — so `emulator.sh up` to boot, then
  `emulator.sh install <apk>`, `emulator.sh launch com.nexopp`, `emulator.sh screenshot <png>`,
  `emulator.sh ui`, and `emulator.sh logcat` for logs. Run `adb-targets.sh` to see which devices and
  the emulator are reachable. Physical devices install via the host adb:
  `adb -s <ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk`.
- **Testing a change on the emulator (the expected loop):** after a green
  `scripts/build.sh`, install the fresh APK and actually drive it:
  1. `emulator.sh install app/build/outputs/apk/debug/app-debug.apk` then
     `emulator.sh launch com.nexopp`.
  2. **Take screenshots** (`emulator.sh screenshot <png>`) and look at them to confirm the UI
     rendered as intended — this is how you *see* the change, not infer it.
  3. **Read the error logs** — `adb logcat` (filter to the app) — to catch crashes, stack
     traces, and warnings the build can't surface.
  4. **Simulate touch / stylus input** — finger presses, taps, and swipes over `adb` (e.g.
     `adb shell input tap <x> <y>` / `input swipe …`, or `emulator.sh ui`) to exercise
     drawing, tool selection, open/save, and other interactions end-to-end.
  Report what the screenshots and logs actually showed; a change isn't verified until it's
  been run this way on the emulator.
- **Promoting to the owner's physical devices — not our job.** Emulator verification is where
  our loop ends. The owner's Android tooling picks up the built APK and moves it into the **BAM
  store**, and the physical devices (Pixel 8a, Galaxy Tab S9 Ultra) install the current build from
  there. Don't `adb install` to those devices as a routine step. (Manual `adb -s <ip>:5555 install
  -r …` over the tailnet still works if you ever need a one-off, but it isn't part of the standard
  flow.)
- **Running the instrumented (`androidTest`) suite:** use `scripts/connected-test.sh`
  (wrapper over `/data/android/.claude/skills/android-dev/scripts/connected-test.sh`), **not**
  Gradle's `connectedDebugAndroidTest`. Gradle's task starts an adb server inside the throwaway
  build container — a different adb world than the emulator — so it dies with "No connected
  devices!". The wrapper instead builds the app + `androidTest` APKs in the builder, reads the
  instrumentation component from the test APK's manifest, then installs and runs it through the
  emulator container's own adb; its exit status is the test result (non-zero if any test fails).
  - `scripts/connected-test.sh` — build + run the whole `SmokeTest` suite on the emulator.
  - `scripts/connected-test.sh -e class com.nexopp.SmokeTest` — extra args pass through to
    `am instrument` (class/method/size filters, etc.).
- **Gotchas:** the emulator needs host KVM (`/dev/kvm`, VT-x enabled in BIOS).

---

## `rnote-cli`

[Rnote](https://github.com/flxzt/rnote) is installed on this box (`/usr/bin/rnote`,
`/usr/bin/rnote-cli`, version 0.14.2) and is the **ground truth for the `.rnote` save
format** — it produces and validates the fixtures under
`app/src/test/resources/fixtures/rnote/`, so no `.rnote` file is ever hand-authored.

- **Import a `.xopp` into a `.rnote`:** `rnote-cli import -i <in.xopp> <out.rnote>`
  (`--xopp-dpi` defaults to 96). Import is currently `.xopp`-only.
- **Create an empty document:** `rnote-cli create <file.rnote>`.
- **Validate:** `rnote-cli test <files…>` — opens each file and parses it; non-zero exit
  if any file is not a valid rnote save. This is the check every committed fixture must pass.
- **Export back out:** `rnote-cli export <files…> doc|doc-pages|selection …` (PDF/SVG/PNG),
  useful for eyeballing what a fixture actually contains.
- **Gotcha:** `import` fails with `Error: Expected file, found directory "<path>"` when the
  **output path does not exist yet** — `touch` the target first.

**Regenerating the fixtures** (from `app/src/test/resources/fixtures/`):

```sh
for f in plain backgrounds layers text-image; do
  touch rnote/$f.rnote && rnote-cli import -i $f.xopp rnote/$f.rnote
done
rnote-cli create rnote/empty.rnote      # only if empty.rnote is missing; create won't overwrite
rnote-cli test rnote/*.rnote            # must exit 0
```

Each `.rnote` mirrors the `.xopp` fixture of the same name (see that directory's `README.md`
for what each one covers); `empty.rnote` is a minimal empty document. The whole set is ~3 KB.
