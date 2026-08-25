# Network Storage V1 specification

## Product intent

Network Storage indexes metadata below explicitly configured SMB roots so a large NAS tree remains browsable offline. Content is later fetched only when requested or retained by a one-way mirror. It is not a microSD replacement, filesystem mount, or `DocumentsProvider`.

The three modes are distinct:

- `INDEX_ONLY`: metadata only.
- `ON_DEMAND`: metadata plus a complete-file, evictable local cache when requested.
- `MIRROR`: metadata plus a retained one-way local copy; remote deletion never automatically deletes the local copy in V1.

## Platform and protocol

- Pixel 9a / current stock Android is the primary target; root is not required.
- SMB2 and SMB3 only, through SMBJ behind a read-only `SmbClient` interface. SMB1 and every remote mutation are prohibited.
- A connection has an ID, display name, host, port, share, root-relative base path, username, protected password, and optional domain.
- Passwords are encrypted with an Android Keystore key, kept outside Room, never logged, and never shown again.

## Current delivery: Steps 0–3

This delivery contains one Android app module using Kotlin, Compose, Hilt, Room, WorkManager, coroutines, and SMBJ.

Users can register a connection and root mode, then start or cancel a manual recursive scan. The scan operates only below `basePath`, normalizes paths, lists through the read-only SMB boundary, and incrementally upserts metadata. Entries contain connection ID, relative/parent path, name, directory flag, `Long` size/mtime, optional MIME type, effective inherited mode, `remoteExists`, and `lastSeenAt`.

Each scan has a durable run record and progress. Existing entries are never cleared at scan start. Only a fully successful scan marks entries not seen in that run as `remoteExists=false`; failed or cancelled scans retain the old index unchanged except for safely upserted observations. The minimal result view reads Room, not SMB.

The indexed browser pages only the selected `connectionId` and `parentPath` from Room, sorts folders before names, supports hierarchical/back/root navigation, and never contacts SMB. Connection and root-index deletion remove only local records and protected credentials after confirmation. DataStore persists an On-demand-only cache limit as `Long` bytes, defaulting to 10 GiB; mirror data is excluded.

## Later steps (not in the current delivery)

4. Complete on-demand download via a bounded buffer and `.part` file.
5. Read-only content URI and `ACTION_VIEW` external open.
6. One-way mirror into shared storage; no deletion propagation.
7. Configurable on-demand-only LRU; mirror exclusion and future pin support.
8. Connected-network periodic mirror work and foreground handling for long work.
9. Polish and acceptance testing.

No file download, mirror data copy, cache/LRU enforcement, FileProvider, `ACTION_VIEW`, periodic sync, VPN integration, thumbnailing, or streaming is part of Steps 0–3.

## Security and errors

Reject absolute paths, `..`, NUL, and root escape. Connection IDs—not display names—separate local identities. Do not accept arbitrary local write paths. Later content grants are read-only. Map backend failures to stable categories (`AUTHENTICATION`, `HOST_NOT_FOUND`, `SHARE_NOT_FOUND`, `CONNECTION`, `TIMEOUT`, `REMOTE_NOT_FOUND`, `LOCAL_STORAGE`, `LOCAL_WRITE`, `SIZE_MISMATCH`, `CANCELLED`, `UNKNOWN`) and do not expose raw backend messages.

## V1 acceptance

The complete V1 will register `NAS/Manga`, index thousands of entries, browse from Room offline, completely download and size-check a CBZ, and open it externally. Mirror sync retains local files after remote deletion. Cache pressure evicts only least-recently-used on-demand files. Steps 0–2 validate registration, SMB listing, durable scanning, cancellation, missing marking, and result inspection only.
