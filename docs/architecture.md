# Architecture

**English** | [日本語](architecture.ja.md)

Network Storage is a single-module, non-root Android application. It indexes a configured SMB subtree and manages complete local files; it does not mount a filesystem or expose a `DocumentsProvider`.

## Boundaries

The presentation layer uses Compose and `MainViewModel` for connection editing, indexed browsing, settings, and worker status. Room stores connection metadata, folder rules, indexed entries, cache metadata, and durable scan runs. `IndexRepository` owns breadth-first scans and reconciles unseen rows only after a fully successful scan.

SMB access stays behind the read-only `SmbClient` interface. `SmbjClient` provides SMB2/3 listing and bounded read handles; no remote mutation API exists. Passwords are AES-GCM encrypted with a non-exportable Android Keystore key and remain separate from Room. Connection-root-relative paths are normalized at configuration, listing, and local-storage boundaries.

## Index and browser

Room exposes a connection- and parent-scoped `PagingSource`, so browsing reads the local index rather than contacting SMB or filtering the entire index in memory. A failed, cancelled, or offline scan retains the prior index. Only completion of a successful scan reconciles NAS-deleted entries.

Connection deletion cascades local metadata and removes the stored credential. Root-index deletion retains the connection. Neither operation crosses the read-only SMB boundary.

## Settings and local storage

`SettingsRepository` is the implemented Preferences DataStore owner for the `Long` cache limit, separate Cache and Mirror SAF tree URIs, automatic Mirror settings, and last-run status. Equal or nested Cache/Mirror trees are rejected when provider document IDs can be compared.

ON_DEMAND and MIRROR have deliberately separate lifecycles:

- **ON_DEMAND:** `CacheRepository` downloads a complete file to `.part` with bounded copying, validates its size, promotes it, then commits cache metadata. Valid entries are touched on access. Cache cleanup evicts least-recently-used on-demand entries as needed to respect the configured limit.
- **MIRROR:** `MirrorRepository` compares indexed NAS metadata with retained device files and copies only NAS-only or NAS-newer content. Mirror files are excluded from cache cleanup. Local-only files are retained and local-newer files are not automatically overwritten.

Completed local files are opened using MIME-specific `ACTION_VIEW` intents with read-only URI permission. A valid completed Mirror file remains openable offline; valid on-demand cache entries can also be reused without a new network transfer.

## Mirror synchronization

Manual Mirror sync and optional automatic Mirror sync use the same conservative NAS → Device policy. Upload, bidirectional synchronization, and resume are absent. Transfers restart at byte zero after interruption, use temporary `.part` files, validate size, and clean partial data on failure or cancellation.

Automatic sync is unique periodic WorkManager work with connected-network and battery-not-low constraints. `SettingsRepository` persists enablement, interval, and last-run outcome; scheduling is restored on application startup. WorkManager intervals are minimum intervals, not exact wall-clock schedules.
