# Test strategy

**English** | [日本語](testing.ja.md)

## Automated coverage

Pure JVM tests cover remote-path normalization and traversal rejection, root-mode inheritance, update detection, `Long` byte accounting, settings validation, cache freshness and limits, bounded copy and size mismatch handling, user-facing error mapping, Mirror comparison direction, local-only/local-newer protection, Copy to SMB decision logic, rule execution gating, source-relative paths, scheduler tags, and tree-copy progress.

The Copy to SMB decision engine is side-effect free and covers `NEW / UNCHANGED / KEEP_BOTH / REPLACE / REUSE_EXISTING`. Execution tests use replaceable SMB/SAF boundaries to cover `.part` handling, size and SHA-256 verification, promotion, conflict policies, backup handling, and cancellation.

Room / Android instrumentation covers successful scan reconciliation, Connection cascade deletion, root-index deletion, parent-scoped paging, Unicode filenames, credential replacement, Preferences DataStore, SAF tree separation, and read-only viewer intents. Folder cache-marker acceptance requires a folder with a cached descendant to expose the marker state and a folder with no cached descendant not to expose it.

## Copy to SMB acceptance checks

At minimum verify:

- Preview performs no SMB mutation.
- An identical original destination becomes `Unchanged`.
- `KEEP_BOTH` selects a valid numbered destination.
- An identical numbered destination becomes `Reuse existing`.
- `REPLACE_WITH_BACKUP` creates the backup before promotion.
- `.part` data is removed on failure and cancellation.
- Source files are never modified or deleted by copying.
- Deletion is never propagated.
- Manual, periodic, and retry work do not interfere across the same rule.
- Activity exposes file-level results and error / backup information.
- Failed source paths can be passed to a retry work request.

## Environment-dependent verification

Room instrumentation, Android Keystore, SAF provider permissions, WorkManager scheduling/cancellation, viewer grants, and real SMB2/3 interoperability require an emulator or physical device.

Real-NAS Copy to SMB acceptance should verify connection-root containment, the fact that Preview and execution are not a locked transaction, real `KEEP_BOTH` / `REPLACE_WITH_BACKUP` behavior, network interruption, large-file `Long` accounting, and `.part` cleanup after cancellation.

Mirror acceptance should verify that only NAS-only / NAS-newer files are copied NAS → Device and that local-only / local-newer files are not deleted or automatically overwritten. Offline acceptance should verify preservation of the previous index and reuse of completed Mirror and valid ON_DEMAND cache data.

## Database migration

Keep production migrations aligned with the Room schemas under `app/schemas`. Prefer validating production migration objects with `MigrationTestHelper` rather than copying SQL into tests.

## Commands

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

`connectedDebugAndroidTest` requires a running emulator or connected device. Install the debug APK with:

```powershell
.\gradlew.bat installDebug
```

The repository currently has no Unix `gradlew` script. Wrapper maintenance is intentionally separate from ordinary application changes.
