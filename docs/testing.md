# Testing strategy

**English** | [日本語](testing.ja.md)

## Automated coverage

Pure JVM tests cover remote-path normalization and traversal rejection, root-mode inheritance, update detection, `Long` byte accounting, settings validation, cache freshness and overflow-safe limits, bounded copy and size mismatch, user-facing network error mapping, Mirror comparison direction, and protection of local-only/local-newer files. The SMB boundary is injectable so fakes exercise listing, read failures, cancellation, and timeouts without real credentials.

Room/device tests cover successful-scan reconciliation, connection cascades, root-index deletion, parent-scoped paging, Unicode names, credential replacement behavior, Preferences DataStore persistence, SAF tree separation, and read-only external-open intents. The exported Room schemas for versions 1, 2, and 3 are retained in `app/schemas`.

Cache and Mirror acceptance checks include `.part` cleanup, complete-size validation, LRU eviction after exceeding the cache limit, Mirror exclusion from cache cleanup, manual and periodic Mirror sync, and conservative handling of local-only/local-newer files. Offline checks verify that a failed scan preserves the prior index, completed Mirror files remain openable, and valid on-demand cache entries can be reused.

## Environment-dependent checks

An emulator or device is required for Room instrumentation, Android Keystore behavior, SAF provider permissions, WorkManager scheduling/cancellation, external viewer grants, and real SMB2/3 interoperability. Real-NAS acceptance should cover a configured root that cannot escape, approximately 1,000 indexed entries, large files (including 2 GiB+ accounting), network interruption, and restart-from-zero behavior because transfer resume is not implemented.

Periodic synchronization should be tested as WorkManager constrained work rather than as an exact timer. Acceptance must confirm that it copies NAS-only/NAS-newer files in the NAS → Device direction, never uploads, does not overwrite local-newer files automatically, and retains local-only files.

## Database migrations

The production database registers migrations 1→2 and 2→3 and exports all three schemas. A dedicated migration instrumentation test is still desirable; it should exercise the production migration objects with Room's `MigrationTestHelper` rather than duplicate their SQL in test code. Until those migration objects have a test-visible home in a separately approved production-code change, schema presence and normal instrumentation/database creation are the available checks.

## Commands

On Windows, run:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
.\gradlew.bat connectedDebugAndroidTest
```

`connectedDebugAndroidTest` requires a running emulator or attached device. The repository currently has no Unix `gradlew` script; wrapper maintenance is intentionally handled separately.
