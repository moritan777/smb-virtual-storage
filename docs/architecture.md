# Architecture

**English** | [日本語](architecture.ja.md)

Network Storage is a single-module, non-root Android application. It indexes a configured SMB subtree into Room and manages complete local files; it does not mount a filesystem or expose a `DocumentsProvider`.

## Boundaries and responsibilities

Compose and ViewModels handle connection editing, local-index browsing, settings, Copy to SMB rule editing, preview, activity, and execution status. Room stores connection metadata, indexed entries, cache metadata, Copy to SMB rules, and copy history.

Normal SMB access stays behind the read-only `SmbClient` interface. Copy to SMB alone uses a separate `SmbCopyClient` and a narrowly scoped write boundary below the connection root. Credentials are protected by Android Keystore and are not stored in Room, Worker input, or copy history. Connection-root-relative paths are normalized at each boundary.

## Index and browser

Room exposes a connection- and parent-scoped PagingSource, so browsing reads the local index rather than contacting SMB. Failed, cancelled, or offline scans retain the previous index; only a successfully completed scan finalizes reconciliation with entries no longer observed remotely.

For directories, the browser projection reports local-data presence when any descendant at any depth has a `CACHED` cache entry. The UI marker means "some cached content exists below this folder"; it does not mean that the whole folder is available offline. File cache state continues to use the existing remote-metadata/local-cache freshness check.

## Local storage

`CacheRepository` downloads complete files to `.part`, validates size, promotes them, and commits cache metadata only after promotion. Valid entries can be reused and cache cleanup follows LRU semantics.

`MirrorRepository` compares NAS metadata with retained files and copies NAS-only / NAS-newer content NAS → Device. Mirror files are excluded from cache cleanup; local-only and local-newer files are not automatically deleted or overwritten.

## Copy to SMB

Copy to SMB is one-way Device → SMB, not bidirectional synchronization. `CopyToSmbOrchestrator` and `CopyToSmbTreeExecutor` perform execution, while `CopyToSmbScheduler` / WorkManager separate manual, periodic, and retry work. Execution is serialized per rule.

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

WorkManager progress is surfaced as total / completed / copied / skipped / failed / current source path / run attempt. Manual copy can be cancelled. A retry path can enqueue only previously failed source-relative paths using `onlyRelativePaths`; retry is a separate unique work stream from manual and periodic execution.

## Security invariants

- SMB boundaries other than Copy to SMB are read-only.
- Copy destinations are connection-root-relative; root escape, absolute paths, and the reserved backup tree as a normal destination are rejected.
- Sources are SAF trees and source files are never modified or deleted.
- Deletion propagation is never inferred.
- `.part` files are never exposed as completed files.
- Preview does not call the write boundary; it only performs the reads required to build a plan.

## WorkManager

Automatic Copy to SMB work follows its configured network, charging, battery, and storage constraints. WorkManager does not guarantee an exact wall-clock execution time. Manual, periodic, and retry work use separate unique work names so the UI can identify the active execution.

Mirror periodic synchronization also uses WorkManager, but remains independent from Copy to SMB work and storage boundaries.
