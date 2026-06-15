# Clean Recovery

Non-root Android file recovery helper. The app recursively scans shared storage for still-accessible files and cache or residue artifacts left by other apps, then copies selected items into public `DataRecovery` folders.

## Non-goal

This app does not perform root-level raw disk recovery or deleted-sector analysis.

## Prerequisites

- JDK 17
- Android SDK with compile SDK 35
- PowerShell on Windows

## Setup

Set `ANDROID_HOME` to your Android SDK, or create a local `local.properties` file:

```properties
sdk.dir=C:/path/to/Android/sdk
```

`local.properties`, `_android_sdk`, `dist`, and build outputs are intentionally ignored by git.

## Build, lint, and test

```powershell
$env:ANDROID_HOME = 'C:\path\to\Android\sdk'
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:testDebugUnitTest
```

## Product behavior

- Non-root, copy-only: the app never deletes or modifies source files.
- Grant broad storage access, then scan images, videos, audio, or documents.
- **Image scans** run a three-phase pipeline with shared deduplication:
  1. **File tree** — recursive walk of shared storage for accessible files.
  2. **MediaStore index/trash** (API 29+, images only) — trash, pending, and index entries; not sector recovery.
  3. **Cache profiles + carving** — known OEM/generic cache paths; JPEG blob carving inside matched containers.
- Video, audio, and document scans use the file tree phase only today.
- Filter results by existing files or suspected deleted/cache artifacts. The deleted badge is honest: only MediaStore trash items are marked suspected deleted.
- Multi-select recovery copies into public `DataRecovery` folders (`content://` URIs and carved blob ranges supported).
