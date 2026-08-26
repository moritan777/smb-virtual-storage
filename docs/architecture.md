# Steps 0–2 architecture

The single `app` module uses a small presentation/domain/data split. Compose and `MainViewModel` render connection registration, WorkManager progress/cancellation, and a bounded Room preview. `IndexRepository` owns the recursive breadth-first scan. Room owns connection metadata, root rules, indexed entries, and durable scan runs.

`SmbClient` is deliberately read-only and exposes only directory listing. `SmbjClient` selects SMBJ because it is SMB2/3-only, mature, and provides the metadata needed here; jcifs-ng remains a possible later replacement behind the interface rather than a second V1 backend. Network work runs on IO and WorkManager provides process-resilient scheduling and cancellation.

Passwords are AES-GCM encrypted with a non-exportable Android Keystore key and stored separately from Room. Remote paths are normalized at configuration and listing boundaries. A scan tags each observation with its run ID, incrementally upserts it, and marks unseen rows missing only in the same transaction that completes a successful run.

Mirror synchronization and automatic cache eviction remain intentionally absent until their specified phases.

## Step 3 browser and settings

Room exposes a `PagingSource` scoped to one connection and one parent path, so navigation never materializes or filters the complete index and never calls SMB. `MainViewModel` owns the selected connection, current relative path, screen, and cached paging stream. Compose consumes it through paging-compose.

Connection deletion is a local Room cascade followed by credential cleanup; root-index deletion transactionally removes the root rule, entries, and scan history while retaining the connection. Neither path crosses the read-only `SmbClient` boundary. `SettingsRepository` is the future cache/LRU contract and persists a single `Long` byte limit in Preferences DataStore, independently of UI and mirror storage.

## Step 4 cache and external open

`SmbClient.openRead` is the only new protocol capability. A unique WorkManager chain serializes downloads. `CacheRepository` maps normalized paths beneath a connection-ID directory in the selected SAF tree, copies with a 64 KiB buffer into `.part`, validates size, promotes, and only then commits `CacheEntryEntity`. Cancellation/failure removes partial data and preserves an older completed cache where present.

Cache and Mirror tree URIs are separate DataStore values; equal/nested document IDs are rejected where the provider exposes comparable IDs. Mirror synchronization remains absent. Valid cached document URIs are touched for future LRU and opened with MIME-specific `ACTION_VIEW` plus read-only permission.

## Step 4.1 presentation

Screen state explicitly separates Connections, Connection Editor, Browser, and Settings. The editor never reads a saved password into UI state: an empty edit preserves the credential and an explicit replacement updates it. The Network folder picker uses read-only share/directory listing and maps the selected share root or nested relative path back to the existing `share`/`basePath` model. Browser presentation reduces each row to folder navigation or cache icon, name, and file size while retaining the Room paging source.
