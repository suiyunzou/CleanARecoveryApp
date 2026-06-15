# CleanRecoveryApp

Native Android app (Java, no third-party deps) that scans device storage to recover still-accessible media/documents (Recovery mode) and to find/delete junk files (Cleaner mode). UI is built programmatically in `MainActivity` — there are no XML layouts. Launcher activity: `com.example.cleanrecovery.MainActivity`.

## Cursor Cloud specific instructions

The build toolchain (JDK 21, Gradle 8.10.2 at `~/gradle`, Android SDK at `~/android-sdk` with platform/build-tools 35, plus `platform-tools` and `emulator`) is preinstalled in the VM snapshot. `~/.bashrc` exports `ANDROID_HOME`/`ANDROID_SDK_ROOT` and adds `gradle`, `adb`, `sdkmanager`, and `emulator` to `PATH`, so a fresh login shell can build and run immediately. The update script regenerates the gitignored `local.properties` (`sdk.dir=$HOME/android-sdk`) on startup.

Build / lint / standard commands (run from repo root, debug = dev build):
- Build dev APK: `./gradlew assembleDebug` (output: `app/build/outputs/apk/debug/app-debug.apk`).
- Lint: `./gradlew lint` (report: `app/build/reports/lint-results-debug.html`).
- The committed Gradle wrapper pins Gradle 8.10.2. If the wrapper is ever missing (e.g. this PR was not merged), regenerate it with `gradle wrapper --gradle-version 8.10.2` or just use the `gradle` on `PATH` instead of `./gradlew`.

Running the app (non-obvious caveats):
- This VM has **no KVM / hardware virtualization** (`/dev/kvm` absent). The Android emulator only runs via slow software emulation (TCG). Boot to `sys.boot_completed=1` takes ~9–12 minutes, and background-process ANR ("isn't responding") dialogs pop up frequently — dismiss them by tapping the "Wait" button (`adb shell input tap 540 1359`). The app itself stays responsive.
- Start headless: `emulator -avd test35 -no-window -no-audio -no-boot-anim -accel off -gpu swiftshader_indirect -no-snapshot`. The `test35` AVD (Pixel 6, API 35 google_apis x86_64) is preconfigured.
- Drive/inspect the headless emulator via adb: `adb shell input tap X Y`, `adb exec-out screencap -p > shot.png`, `adb shell am start -n com.example.cleanrecovery/.MainActivity`.
- Storage gotcha 1: push test files into `/sdcard/...` **only after** `sys.boot_completed=1` AND the emulated-storage volume is `mounted` (`adb shell sm list-volumes emulated`). Files pushed earlier are shadowed when the FUSE storage mount finishes initializing. Under heavy load the FUSE mount can crash ("Transport endpoint is not connected"); recover it with `adb shell sm unmount emulated\;0 && adb shell sm mount emulated\;0`, then re-push.
- Storage gotcha 2: the app needs `MANAGE_EXTERNAL_STORAGE` ("All files access") to scan. Granting it via `adb shell appops set com.example.cleanrecovery MANAGE_EXTERNAL_STORAGE allow` flips the appop but does **not** reliably elevate the app's FUSE mount to full-access on this emulator image, so scans of `/sdcard` may return nothing even though the files exist. The image-recovery scan additionally walks `/system` (always readable), which is a reliable way to see the scan engine working end-to-end.
