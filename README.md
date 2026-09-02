# Network Storage

**English** | [日本語](README.ja.md)

<img width="1536" height="1024" alt="Network Storage screenshots" src="https://github.com/user-attachments/assets/fbcfbda1-51e1-4fe6-bfed-d1ee2592c7b5" />

**Status: Public Beta / v0.1.x development line**

Network Storage is a non-root Android 10+ app that stores a user-selected SMB2/SMB3 NAS subtree in a local Room index and handles requested files as complete local files. It is not an Android filesystem mount.

## What it does

- **ON_DEMAND** — scans NAS metadata, downloads a complete file when opened, and keeps it in an evictable local cache. The cache is cleaned by LRU when the configured limit is exceeded.
- **MIRROR / Keep offline** — retains complete local copies for offline use. Manual sync and optional WorkManager periodic sync are **one way, NAS → Device**. Mirror files are excluded from cache cleanup.
- **COPY_TO_SMB / Copy to SMB** — copies a user-selected Android Storage Access Framework source tree one way into a destination below the configured SMB connection root. Source files are never deleted, moved, or modified.
- **Copy to SMB Preview** — shows SHA-256-based `New / Unchanged / Keep both / Replace + backup / Reuse existing` decisions before execution. Preview itself does not modify SMB.
- **Copy to SMB Activity / progress / cancellation** — shows WorkManager state, file counts, current source path, and copied/skipped/failed counts, and allows manual copy cancellation. A failed-file-only retry path is also implemented in the execution backend.
- Browses a Room-backed local index. Normal browser navigation does not contact SMB directly.
- Opens only complete local files through read-only Android content URIs.
- Uses SMB2/SMB3. **SMB1 is not supported.** Normal SMB access is read-only; only Copy to SMB uses a narrowly scoped write boundary.

## Offline behavior

An offline or failed scan preserves the previous Room index. Completed Mirror files remain openable offline. Valid ON_DEMAND cache entries can also be reused without a network transfer. Files that have not been downloaded cannot be fetched offline.

A folder shows a local-data marker only when **some cached file exists anywhere below that folder**. The marker is navigation guidance; it does not mean that the entire folder is available offline.

## Copy to SMB

Copy to SMB is not bidirectional synchronization. It is **Device → SMB only**.

- The source is a SAF-selected folder.
- The destination is relative to the configured connection root.
- `KEEP_BOTH` and `REPLACE_WITH_BACKUP` are supported.
- Identical content is detected with SHA-256 and can be reported as `Unchanged` or `Reuse existing` rather than creating unnecessary duplicates.
- Conflicts either preserve the original SMB file or move it into `.network-storage-backup` before replacement.
- Transfers use `.part` → verification → promotion, so incomplete files are never exposed as completed results.
- There is no deletion propagation, post-upload source deletion, or arbitrary SMB write path.
- Preview is a dry-run: it performs no create, modify, rename, or delete operation.

## Getting started

1. In **Settings**, select SAF folders for ON_DEMAND Cache and Mirror / Keep offline data, and optionally set the cache limit.
2. Add the NAS host, share/base folder, username, and password; choose ON_DEMAND or MIRROR as needed.
3. Run a scan and browse the indexed folders.
4. In ON_DEMAND, open a file to download it completely before launching a viewer.
5. In MIRROR / Keep offline, run a manual update or enable periodic update.
6. For Copy to SMB, create a rule for the connection and configure the source folder, destination, conflict policy, and automatic execution constraints. Use **Preview** to inspect the planned operations before copying.

Credentials are protected with Android Keystore and are not shown again after saving.

## Build prerequisites

- JDK 17 or newer. Gradle 8.11.1 officially supports running on JDK 21.
- Android SDK with compileSdk 35 (targetSdk 35; minSdk 29 / Android 10)
- Windows PowerShell or Command Prompt
- An emulator or Android 10+ device for instrumentation tests and debug runs

The repository includes `gradlew.bat` for Windows. A Unix `gradlew` script is not currently included. Do not regenerate wrapper files as part of ordinary application changes.

### Windows: test, build, and install

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

With an emulator/device visible to `adb devices`, build and install the debug APK with:

```powershell
.\gradlew.bat installDebug
```

Instrumentation tests require a running emulator or connected device:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

## Known limitations

- Public Beta development line. `feature/copy-decision-dry-run` includes Copy to SMB Preview, decision planning, Activity, cancellation, and a failed-file retry backend path.
- No SMB1, bidirectional sync, deletion propagation, streaming, partial-file open, or transfer resume. Interrupted transfers restart from byte zero.
- WorkManager determines the execution time for automatic Copy to SMB; it is not an exact clock schedule.
- Copy to SMB Preview does not lock the SMB state. A concurrent SMB change after Preview can change the final execution decision.
- Mirror local-only and local-newer files are conservatively retained and are not automatically deleted or overwritten.
- Real-device behavior depends on the selected document provider, local storage, network stability, and an installed viewer for the file type.

## Documentation

See [`docs/architecture.md`](docs/architecture.md) for architecture and boundaries, [`docs/testing.md`](docs/testing.md) for the test strategy, [`docs/copy-to-smb-v1-audit.md`](docs/copy-to-smb-v1-audit.md) for the Copy to SMB implementation audit, and [`docs/v1-spec.md`](docs/v1-spec.md) for the V1 product specification.

## License

Licensed under the [Apache License 2.0](LICENSE).
