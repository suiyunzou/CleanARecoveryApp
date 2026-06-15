# CleanRecoveryApp

Single-module native **Android** app (Java, Gradle) — "Clean Recovery". It has two
on-device modes: **Recovery** (scan storage for images/videos/audio/docs and copy them
into public `DataRecovery` folders) and **Cleaner** (scan `/sdcard` for junk — logs, temp,
caches, etc. — and delete selected items). There is **no backend, database, or network
service**; everything runs on-device against the Android filesystem.

Standard commands live in `build.gradle` / `app/build.gradle`. Source: `app/src/main/java/com/example/cleanrecovery/`.

## Cursor Cloud specific instructions

Environment is pre-provisioned in the VM snapshot. `~/.bashrc` exports `ANDROID_HOME`,
`ANDROID_SDK_ROOT`, and `PATH` (Gradle 8.9 + Android SDK tools). Use a login/interactive
shell so these are loaded; otherwise `export ANDROID_HOME=$HOME/android-sdk` and add
`$HOME/gradle-dist/gradle-8.9/bin`, `$ANDROID_HOME/platform-tools`, `$ANDROID_HOME/emulator`
to `PATH`. There is **no Gradle wrapper** (`gradlew`) in the repo — invoke `gradle` directly.

Common tasks (run from repo root):
- Build debug APK: `gradle :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- Lint: `gradle :app:lintDebug`
- Unit tests: `gradle :app:testDebugUnitTest` (no tests exist yet → `NO-SOURCE`, passes)

### Running the app on an emulator (important gotchas)

The Cloud VM has **no KVM / hardware virtualization** (`/dev/kvm` absent). Consequences:
- The Google emulator **cannot** run ARM images on this x86_64 host, and x86 images need
  acceleration. The **only** way to boot is an **x86_64** image with **software emulation**:
  `emulator -avd <name> -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel off -qemu -m 2560`.
  This is TCG (software) — boot takes several minutes; be patient and poll
  `adb shell getprop sys.boot_completed`.
- **Use an API 29 (Android 10) image** (`system-images;android-29;google_apis;x86_64`).
  On **API 35** the FUSE-based `/sdcard` crashes under TCG (volume stuck `checking` /
  "Transport endpoint is not connected"), so storage scans don't work. API 29 uses kernel
  `sdcardfs` and mounts `/sdcard` reliably. An AVD named `test29` is already created.
- Headless run: drive the UI via `adb shell input tap X Y` (get coordinates from
  `adb shell uiautomator dump`), capture stills with `adb exec-out screencap -p > f.png`,
  and record demos with `adb shell screenrecord` (the host screen recorder won't see a
  `-no-window` emulator).

### App testing notes
- Recovery mode requires storage access. On API 29 grant it with
  `adb shell pm grant com.example.cleanrecovery android.permission.READ_EXTERNAL_STORAGE`
  (and `WRITE_EXTERNAL_STORAGE`); on API 30+ it needs `MANAGE_EXTERNAL_STORAGE` via appops.
- Recovery **Images** validates real image bytes with `BitmapFactory` — placeholder files
  with image extensions are ignored; push a **real** image to `/sdcard` to see a hit. It
  scans `/sdcard` first, then `/system` (slow), so found items appear early.
- Cleaner **Scan Junk** only scans `/sdcard` (fast, bounded). Found junk items are
  auto-selected, so "Delete Selected Junk" works without tapping "Select All".
