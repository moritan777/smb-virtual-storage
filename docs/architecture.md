# Architecture

**English** | [日本語](architecture.ja.md)

Network Storage is a single-module, non-root Android application. It indexes a configured SMB subtree into Room and manages complete local files; it does not mount a filesystem or expose a `DocumentsProvider`.

## Boundaries and responsibilities

Compose and ViewModels handle connection editing, local-index browsing, general settings, the dedicated Backup area, Copy to SMB rule editing, preview, activity, and execution status. Room stores connection metadata, indexed entries, cache metadata, Copy to SMB rules, and copy history.

The Backup area reuses the existing connection and Copy to SMB rule model. It provides a focused NAS-connection workflow and a per-connection list of phone-folder backup rules; it does not introduce a second transfer engine. Its destination picker lists folders below the selected share and connection root, supports parent navigation and selecting the current folder, and creates new directories only through the explicit `createDirectory` operation after validating a root-relative path.

SMB listing and file reads stay behind `SmbClient`. It also exposes one explicit `createDirectory` operation for the Backup NAS folder picker, limited to creating directories below the selected connection root; it does not provide arbitrary file writes. Copy to SMB file transfers alone use the separate `SmbCopyClient` and its narrowly scoped write boundary below the connection root. Credentials are protected by Android Keystore and are not stored in Room, Worker input, or copy history. Connection-root-relative paths are normalized at each boundary.

## Index and browser

Room exposes a connection- and parent-scoped PagingSource, so browsing reads the local index rather than contacting SMB. Failed, cancelled, or offline scans retain the previous index; only a successfully completed scan finalizes reconciliation with entries no longer observed remotely.

For directories, the browser projection reports local-data presence when any descendant at any depth has a `CACHED` cache entry. The UI marker means "some cached content exists below this folder"; it does not mean that the whole folder is available offline. File cache state continues to use the existing remote-metadata/local-cache freshness check.

## Local storage

`CacheRepository` downloads complete files to `.part`, validates size, promotes them, and commits cache metadata only after promotion. Valid entries can be reused and cache cleanup follows LRU semantics.

`MirrorRepository` compares NAS metadata with retained files and copies NAS-only / NAS-newer content NAS → Device. Mirror files are excluded from cache cleanup; local-only and local-newer files are not automatically deleted or overwritten.

## Copy to SMB

Copy to SMB is one-way Device → SMB, not bidirectional synchronization. `CopyToSmbOrchestrator` and `CopyToSmbTreeExecutor` perform execution, while `CopyToSmbScheduler` / WorkManager separate manual, daily, and retry work. Execution is serialized per rule.

### Preview and decision planning

`CopyDryRunPlanner` reads source and existing destination content as needed, including SHA-256 hashing, and builds a plan without writing to SMB. The shared decision vocabulary is:

- `NEW`
- `UNCHANGED`
- `KEEP_BOTH`
- `REPLACE`
- `REUSE_EXISTING`

Identical original or numbered destinations can be reused instead of producing unnecessary duplicates. Preview is a dry-run and performs no create, write, rename, or delete operation.

### Safe execution

A Copy to SMB upload follows `.part` creation → bounded copy → size / SHA-256 verification → conflict decision → promotion → practical post-verification → history recording. `KEEP_BOTH` uses numbered names. `REPLACE_WITH_BACKUP` moves the existing destination into `.network-storage-backup` before promotion. Backup and `.part` trees are excluded from normal browsing.

### Progress, history, cancellation, and retry

WorkManager progress is surfaced as total / completed / copied / skipped / failed / current source path / run attempt. Manual copy can be cancelled. A retry path can enqueue only previously failed source-relative paths using `onlyRelativePaths`; retry is a separate unique work stream from manual and daily execution. The retry operation is available in the application layer, while a dedicated failed-only retry button is not currently exposed in the Copy to SMB card UI.

## Backup settings and daily execution

`BackupSettingsScreen` provides the dedicated Backup entry point. A user can add a NAS connection by entering host, port, credentials, optional domain, and SMB share name. The connection is checked before it is accepted. Selecting a connection opens its backup-rule workflow, which reuses the Copy to SMB rule editor and transfer pipeline. Backup simple UI rules (`simpleMode = true`) provide **Test**, **Run now**, and **Edit backup** actions on each rule card. **Run now** invokes the existing manual Copy to SMB execution pathway (`copyNow` / `CopyToSmbScheduler.enqueueManual`), bypassing automatic scheduling constraints (daily time, charging requirement, network policy) while reusing existing unique work (`ExistingWorkPolicy.KEEP`) and execution locks without introducing a separate transfer engine. The NAS destination picker lists folders through `SmbClient.list`, supports parent navigation and current-folder selection, and creates new directories through `SmbClient.createDirectory`. Folder creation retrieves the saved credential for the selected connection and clears the password character array after the operation.

A rule stores the SAF source tree, destination directory relative to the configured SMB root, conflict policy, subfolder behavior, and optional daily schedule settings. Deleting a connection removes app-side connection data, related backup rules/history, local index data, and saved credentials; it does not delete NAS files or the phone's source files.

Daily execution is implemented as a chain of one-time WorkManager requests rather than a precise clock alarm:

1. The scheduler calculates the next occurrence of the rule's local wall-clock time and enqueues a uniquely named request for that rule.
2. When a scheduled worker starts, it queues the next occurrence so the daily chain can continue.
3. The worker checks the rule's charging and network policy (any connected network, unmetered network, or Wi-Fi) at execution time.
4. If a configured condition is not met, the run is recorded as skipped in history and finishes without copying.

WorkManager and Android background-execution policy can delay a request. A skipped run is recorded; the implementation does not guarantee another attempt later that same day. Manual runs have a connected-network WorkManager constraint and use a separate unique-work name from daily and retry runs.

## Security invariants

- SMB boundaries other than Copy to SMB are read-only.
- Copy destinations are connection-root-relative; root escape, absolute paths, and the reserved backup tree as a normal destination are rejected.
- Sources are SAF trees and source files are never modified or deleted.
- Deletion propagation is never inferred.
- `.part` files are never exposed as completed files.
- Preview does not call the write boundary; it only performs the reads required to build a plan.

## WorkManager

Manual, daily, and retry Copy to SMB work use separate unique work names. Daily work uses a per-rule one-time request chain and local-time calculation; it is not an exact-time alarm. The worker performs the rule-specific charging/network checks and records skipped runs. Startup reconciliation restores missing daily work for enabled rules and cancels scheduled work for disabled rules.

Mirror periodic synchronization also uses WorkManager, but remains independent from Copy to SMB work and storage boundaries.
