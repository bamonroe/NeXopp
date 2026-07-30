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

The pipeline that compiles and packages the Android app. Per `CLAUDE.md`, builds run
**inside a container** (Docker first, Podman fallback) so the SDK toolchain is pinned and the
host stays clean.

- **Where it lives:** repo root — `Dockerfile` (the pinned Android SDK image: cmdline-tools +
  `platforms;android-34` + `build-tools;34.0.0` on Temurin JDK 17), `compose.yaml` (the
  `build` service that mounts the repo and caches `~/.gradle` in a named volume), and
  `scripts/build.sh` (the entry point). The Gradle project itself is `settings.gradle.kts` +
  `app/` with the wrapper pinned to Gradle 8.9.
- **How to run it:**
  - `scripts/build.sh` — the default check loop: `testDebugUnitTest assembleDebug` in the
    container (auto-selects docker, else podman).
  - `scripts/build.sh <tasks…>` — arbitrary Gradle tasks, e.g. `scripts/build.sh testDebugUnitTest`
    (unit tests only) or `scripts/build.sh clean assembleDebug`.
  - Direct equivalent: `docker compose run --rm build`.
- **Outputs:** debug APK at `app/build/outputs/apk/debug/app-debug.apk`; unit-test reports at
  `app/build/reports/tests/testDebugUnitTest/`.
- **Gotchas:**
  - First run builds the image (downloads the Android SDK) and Gradle downloads dependencies —
    minutes and hundreds of MB. The `gradle-cache` volume makes subsequent runs fast; don't
    delete it casually.
  - `--no-daemon` is used in-container (the container is ephemeral; a daemon would just be
    killed). Don't add a daemon.
  - The SDK licenses are accepted in the image build (`sdkmanager --licenses`); no
    `local.properties` is needed — `ANDROID_HOME` is set in the image.
  - The `RealFileRoundTripTest` reads the repo-root `udiff.xopp` (mounted into the container);
    it self-skips when absent, so it's green with or without the sample.

## Android emulator

The device/emulator harness used to run and test the app on a virtual device. (Instrumented
tests and manual runs; unit tests need no device — they run in the build container above.)

- **Where it lives:** not yet scripted. The intended harness: an AVD created from an SDK
  system image (`system-images;android-34;google_apis;x86_64`) via `sdkmanager` + `avdmanager`.
- **How to run it (intended):**
  - Create once: `avdmanager create avd -n xopp -k "system-images;android-34;google_apis;x86_64"`.
  - Launch headless: `emulator -avd xopp -no-window -no-audio -gpu swiftshader_indirect`.
  - Install & launch: `adb install -r app/build/outputs/apk/debug/app-debug.apk` then
    `adb shell am start -n com.xopp.android/.MainActivity`.
- **Gotchas:** the emulator needs host KVM (`/dev/kvm`) — hard to nest inside the build
  container, so run it on the host or a KVM-enabled runner, not the build image. Prefer
  `-no-snapshot-load` for clean boots in CI. Left as a TODO to script (see `TODO.md`).
