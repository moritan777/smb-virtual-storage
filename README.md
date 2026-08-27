# Network Storage

**English** | [日本語](README.ja.md)

<img width="1536" height="1024" alt="Image" src="https://github.com/user-attachments/assets/fbcfbda1-51e1-4fe6-bfed-d1ee2592c7b5" />

**Status: Public Beta / v0.1.0**

Network Storage is a non-root Android 10+ app that indexes a user-selected SMB2/SMB3 NAS subtree and opens complete files from local device storage. It is **not** an Android filesystem mount, does not expose the NAS to Android as a mounted drive, and is not a microSD replacement.

## What it does

- **ON_DEMAND** — scans NAS metadata, downloads a complete file when opened, and keeps it in an evictable local cache. On-demand cache entries are subject to LRU cleanup when the configured cache limit is exceeded.
- **MIRROR** — retains complete local copies for offline use. Manual sync and optional WorkManager periodic sync copy files **one way, NAS → Device**. Mirror files are retained data and are excluded from cache cleanup.
- Browses a Room-backed local index and opens completed local files through read-only Android content URIs.
- Uses SMB2/SMB3. **SMB1 is not supported.** Remote SMB access is read-only.

Upload and bidirectional sync are not implemented. Transfer resume is not implemented; an interrupted transfer restarts from the beginning. Mirror sync does not automatically delete local-only files or overwrite local-newer files.

## Offline behavior

An offline or failed scan preserves the prior index instead of treating the NAS contents as deleted. Valid completed Mirror files can be opened offline. Files that have not been downloaded in ON_DEMAND mode cannot be fetched while offline; an already valid local on-demand cache entry can still be opened.

## Getting started

1. In **Settings**, select separate Android Storage Access Framework folders for on-demand Cache and Mirror data, and optionally set the cache limit.
2. Add a connection with the NAS host, SMB share/base folder, username, and password; choose **ON_DEMAND** or **MIRROR**.
3. Run a scan, then browse the indexed folders.
4. In ON_DEMAND mode, open a file to download it completely and launch an installed viewer.
5. In MIRROR mode, run manual sync, or enable automatic sync and choose its periodic interval; open completed Mirror files online or offline.

Credentials are protected with Android Keystore and are not shown again after saving.

## Build prerequisites

- JDK 17
- Android SDK with compileSdk 35 (targetSdk 35; minSdk 29 / Android 10)
- Windows PowerShell or Command Prompt for the wrapper commands below
- An emulator or Android 10+ device for installation and instrumentation tests

The repository currently includes `gradlew.bat` for Windows. A standard Unix `gradlew` wrapper script is **not currently included**, so Linux/macOS builds require a separately installed compatible Gradle or a future human-managed wrapper update. Do not regenerate wrapper files as part of ordinary application changes.

### Windows: build and install a debug APK

From the repository root:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

The debug APK is written under `app\build\outputs\apk\debug\`. With an emulator/device attached, install it with:

```powershell
.\gradlew.bat installDebug
```

## Known limitations

- Public Beta; only the current `main` / v0.1.x line is supported.
- No SMB1, upload, remote mutation, bidirectional sync, streaming, partial-file open, or transfer resume.
- This is an app-local index/cache/mirror, not a system-wide mount or storage-volume replacement.
- Periodic Mirror sync timing is controlled by Android WorkManager and is not an exact schedule.
- Mirror conflict handling is conservative: local-only and local-newer files require user management and are not automatically deleted.
- Real-device behavior depends on the chosen document provider, available local storage, network stability, and an installed viewer for the file type.

## License

Licensed under the [Apache License 2.0](LICENSE).
