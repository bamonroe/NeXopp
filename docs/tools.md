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
  - Direct equivalent: `/data/android/build.sh /home/bam/git/personal/xopp_android <tasks…>`.
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
  - The `RealFileRoundTripTest` reads the repo-root `udiff.xopp` (visible via the parent
    mount); it self-skips when absent, so it's green with or without the sample.

## Android emulator

Running the APK on a device/emulator also goes through `/data/android` — a headless Android 14
emulator you drive over `adb`, plus physical devices on the tailnet (see its `config.yaml`).

- **Where it lives:** `/data/android/` — `docker-compose.yml` (the emulator container) and
  `.claude/skills/android-dev/scripts/emulator.sh` (the driver). Full details in that
  directory's `README.md`; don't duplicate them here.
- **How to run it:** `emulator.sh up` / `status` to boot, then `emulator.sh install <apk>`,
  `emulator.sh launch com.xopp.android`, `emulator.sh screenshot <png>`, `emulator.sh ui`.
  Physical devices: `adb -s <ip>:5555 install -r app/build/outputs/apk/debug/app-debug.apk`.
- **Gotchas:** the emulator needs host KVM (`/dev/kvm`, VT-x enabled in BIOS). Wiring a
  `.xopp` round-trip smoke test on the emulator is still a TODO (see `TODO.md`).
