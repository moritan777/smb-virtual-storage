# Network Storage

Network Storage is a non-root Android app for browsing selected SMB2/SMB3 NAS folders through a local metadata index. It is **not** an Android filesystem mount or microSD replacement.

The current UI exposes two storage modes:

- **On-demand** — scan NAS metadata, browse the local index, download a complete file when it is opened, and keep it in an evictable local cache.
- **Mirror** — retain complete local copies for offline use. Manual and optional periodic sync copy NAS-only or NAS-newer files to the device. Local-only and local-newer mirror files are not automatically deleted or overwritten.

Network Storage does not currently upload local files back to the NAS.

## Implemented features

- SMB2/SMB3 connection registration with protected credentials.
- Network folder picker and compact connection/browser UI.
- Room-backed metadata index and indexed browsing.
- Safe scan reconciliation: an unreachable NAS does not turn a failed scan into remote deletions.
- On-demand complete-file downloads through a user-selected SAF cache tree.
- Cache size limit and eviction of removable cached files.
- Read-only external opening through Android content URIs.
- Mirror comparison and one-way NAS-to-device synchronization.
- Offline opening of current mirrored files.
- Optional automatic Mirror sync with configurable interval and last-run status.
- Foreground execution for long downloads/synchronization.
- Shared user-facing error classification for scan, download, and Mirror operations.

## Data safety

On-demand Cache and Mirror use separate user-selected SAF trees.

Downloads and Mirror copies are written to temporary `.part` files first. A file is promoted to its final name only after the copy completes and its size matches the indexed NAS metadata. Interrupted copies are discarded rather than exposed as complete files.

A successful scan may remove NAS-deleted entries from the metadata index. A failed or offline scan does not perform that reconciliation. Mirror storage is intentionally retained: if a NAS file disappears, an existing device-side Mirror copy becomes local-only and is not automatically deleted.

Automatic Mirror sync only downloads `REMOTE_ONLY` and `REMOTE_NEWER` items. It does not automatically overwrite `LOCAL_NEWER` files.

## Automatic Mirror sync

Automatic sync is optional and uses Android WorkManager. The configured interval is restored when the app process starts. Work runs only when its Android constraints are satisfied and records the latest outcome in Settings.

Periodic scheduling is controlled by Android/WorkManager, so the selected interval is a minimum scheduling interval rather than an exact wall-clock execution guarantee.

## Acceptance coverage

Unit tests cover cache boundaries, complete-file copy validation, interrupted-download failure, stable user-facing network errors, Mirror diff direction, and protection of local-only/local-newer Mirror files.

Manual NAS acceptance testing has also covered:

- cache eviction after exceeding the configured limit;
- offline scan without destructive reconciliation;
- Mirror open while online and offline;
- automatic Mirror synchronization of a NAS-side change;
- interrupted Mirror transfer followed by a clean restart from the beginning.

Resume of a partially transferred SMB file is not implemented; interrupted transfers restart from byte zero.

## Build

Use JDK 17 and an Android SDK compatible with the project, then run from the repository root:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

For an attached emulator/device:

```powershell
.\gradlew.bat installDebug
```
