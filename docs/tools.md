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
**inside a container** (Docker first, Podman fallback) so the SDK/NDK toolchain is pinned
and the host stays clean.

- **Where it lives:** `[Gradle project root / Dockerfile path — fill in]`
- **How to run it:** `[e.g. docker compose run --rm build ./gradlew assembleDebug — fill in]`
- **Outputs:** `[e.g. app/build/outputs/apk/… — fill in]`
- **Gotchas:** `[stale Gradle caches, required clean builds, signing keys/keystore, SDK
  licenses — fill in]`

## Android emulator

The device/emulator harness used to run and test the app on a virtual device.

- **Where it lives:** `[AVD config / emulator launch script — fill in]`
- **How to run it:** `[e.g. emulator -avd <name> -no-window, then adb install <apk> — fill in]`
- **Gotchas:** `[KVM/host virtualization requirements, headless flags, cold-boot vs snapshot,
  adb port/connection issues — fill in]`
