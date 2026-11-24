# Network Storage

**English** | [日本語](README.ja.md)

<img width="1536" height="1024" alt="Network Storage screenshots" src="https://github.com/user-attachments/assets/fbcfbda1-51e1-4fe6-bfed-d1ee2592c7b5" />

**Status: Public Beta / v0.1.x development line**

Network Storage is a non-root Android 10+ app that stores a user-selected SMB2/SMB3 NAS subtree in a local Room index and handles requested files as complete local files. It is not an Android filesystem mount.

## What it does

- **Backup settings** — provides a dedicated backup area where NAS connections and backup rules can be created, edited, reviewed, and deleted. A rule selects a phone folder, a NAS destination, and optional daily execution settings.
- **ON_DEMAND** — scans NAS metadata, downloads a complete file when opened, and keeps it in an evictable local cache. The cache is cleaned by LRU when the configured limit is exceeded.
- **MIRROR / Keep offline** — retains complete local copies for offline use. Manual sync and optional WorkManager periodic sync are **one way, NAS → Device**. Mirror files are excluded from cache cleanup.
- **COPY_TO_SMB / Copy to SMB** — copies a user-selected Android Storage Access Framework source tree one way into a destination below the configured SMB connection root. Source files are never deleted, moved, or modified.
- **Copy to SMB Preview** — shows SHA-256-based `New / Unchanged / Keep both / Replace + backup / Reuse existing` decisions before execution. Preview itself does not modify SMB.
- **NAS destination folder picker** — the backup-rule editor can browse the NAS folder hierarchy, move to a parent folder, select a destination, and create a new folder at the current location when needed.
- **Copy activity and history** — shows WorkManager state, progress, file counts, current source path, and copied/skipped/failed results. Manual copy can be cancelled; a failed-file-only retry path is implemented in the backend.
- **Daily automatic backup** — each rule can use a local time, network requirement, and optional charging requirement. WorkManager may delay execution; if a configured condition is not met when a run starts, that run is skipped and recorded in history.
- Browses a Room-backed local index. Normal browser navigation does not contact SMB directly.
- Opens only complete local files through read-only Android content URIs.
- Uses SMB2/SMB3. **SMB1 is not supported.** Normal SMB access is read-only; only Copy to SMB uses a narrowly scoped write boundary.

## Offline behavior

An offline or failed scan preserves the previous Room index. Completed Mirror files remain openable offline. Valid ON_DEMAND cache entries can also be reused without a network transfer. Files that have not been downloaded cannot be fetched offline.

A folder shows a local-data marker only when **some cached file exists anywhere below that folder**. The marker is navigation guidance; it does not mean that the entire folder is available offline.

## Copy to SMB and phone backups

Copy to SMB is not bidirectional synchronization. It is **Device → SMB only**. The dedicated Backup area uses this same one-way copy mechanism.

- The source is a SAF-selected folder.
- The destination is relative to the configured connection root.
- `KEEP_BOTH` and `REPLACE_WITH_BACKUP` are supported.
- Identical content is detected with SHA-256 and can be reported as `Unchanged` or `Reuse existing` rather than creating unnecessary duplicates.
- Conflicts either preserve the original SMB file or move it into `.network-storage-backup` before replacement.
- Transfers use `.part` → verification → promotion, so incomplete files are never exposed as completed results.
- There is no deletion propagation, post-upload source deletion, or arbitrary SMB write path.
- Preview is a dry-run: it performs no create, modify, rename, or delete operation.

## Getting started

1. Open **Backup** and add a NAS connection. Enter the host, port, credentials, and SMB share name; the app checks the connection before continuing.
2. Select the NAS connection and create a backup rule.
3. Choose a folder on the phone, then use **Browse NAS folders** to navigate the NAS hierarchy and select the destination. Use **New folder** when a destination folder needs to be created. Optionally enable daily backup and configure its local time, network, and charging conditions.
4. Use the rule history to review previous runs. Rules can be edited or deleted from the connection's backup list.
5. For general NAS browsing, configure ON_DEMAND Cache or MIRROR / Keep offline storage in **Settings**, add/select a connection, and run a scan.

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

- Public Beta development line. This development branch includes Copy to SMB Preview, decision planning, activity/history, cancellation, and the failed-file retry backend.
- No SMB1, bidirectional sync, deletion propagation, streaming, partial-file open, or transfer resume. Interrupted transfers restart from byte zero.
- WorkManager controls when automatic Copy to SMB work runs; the configured local time is a target, not an exact execution guarantee. A run skipped for unmet conditions is recorded and is not guaranteed to be retried that day.
- Copy to SMB Preview does not lock the SMB state. A concurrent SMB change after Preview can change the final execution decision.
- Mirror local-only and local-newer files are conservatively retained and are not automatically deleted or overwritten.
- Real-device behavior depends on the selected document provider, local storage, network stability, and an installed viewer for the file type.

## Documentation

See [`docs/architecture.md`](docs/architecture.md) for architecture and boundaries, [`docs/testing.md`](docs/testing.md) for the test strategy, [`docs/copy-to-smb-v1-audit.md`](docs/copy-to-smb-v1-audit.md) for the Copy to SMB implementation audit, [`docs/v1-spec.md`](docs/v1-spec.md) for the V1 product specification, and [`docs/v1-implementation-addendum.md`](docs/v1-implementation-addendum.md) for current implementation extensions.

## License

Licensed under the [Apache License 2.0](LICENSE).
