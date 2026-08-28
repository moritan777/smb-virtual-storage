# Network Storage V1 specification

## Product intent

Network Storage is a non-root Android application for browsing and using files stored below explicitly configured SMB roots.

The application indexes SMB metadata into a local Room database so that a large NAS tree remains browsable without repeatedly listing the remote server.

File content is handled in one of three user-facing modes:

- downloaded only when requested,
- retained locally for offline access,
- copied from a user-selected device folder to a scoped SMB destination.

Network Storage is not:

- a microSD replacement exposed as an Android storage volume,
- a filesystem mount,
- a FUSE or root mount,
- a `DocumentsProvider`,
- a streaming filesystem,
- a VPN,
- a bidirectional synchronization system.

The application must never expose a partial file as a completed file.

## User-facing modes

### ON_DEMAND

`ON_DEMAND` stores SMB metadata in Room and downloads a complete file only when the user requests it.

Requirements:

- SMB access is read-only.
- File content is downloaded into an evictable local cache.
- A valid completed cache entry may be reused while offline.
- A file that has not been downloaded cannot be opened while SMB is unavailable.
- Cache content may be evicted according to the On-demand cache policy.
- On-demand cache content must never be treated as retained Keep offline data.
- Opening a file must never stream SMB content directly to a viewer.
- Only a completely downloaded and verified local file may be opened.

### MIRROR / Keep offline

`MIRROR` is the legacy internal mode name for the user-facing Keep offline feature.

Keep offline stores SMB metadata in Room and retains complete local file copies for offline access.

Requirements:

- SMB access is read-only.
- Copy direction is SMB to device only.
- Manual update and optional WorkManager periodic update are supported.
- SMB-only and SMB-newer files may be copied to the device.
- Device-only and device-newer files must not be uploaded to SMB.
- Remote deletion must not automatically delete the retained local file.
- Local deletion must not delete the SMB file.
- Keep offline files are retained data and are excluded from On-demand cache eviction.
- Only complete and verified local files may be opened.

### COPY_TO_SMB / Copy to SMB

`COPY_TO_SMB` is a device-to-SMB copy feature.

It copies files from a user-selected Android Storage Access Framework source tree to a configured destination below an SMB connection root.

Copy to SMB is not bidirectional synchronization.

Requirements:

- Copy direction is device to SMB only.
- Manual copy and optional WorkManager automatic copy are supported.
- Source files on the device must never be deleted, renamed, or moved by Network Storage.
- A missing device source file must not delete an SMB file.
- A missing SMB file must not delete a device source file.
- Deletion must not propagate in either direction.
- SMB files that do not correspond to a current source file must remain unchanged.
- Every SMB write must remain below the configured connection root.
- Copy to SMB must use a separate narrowly scoped SMB write boundary.
- On-demand, indexing, remote browsing, and Keep offline must not use the write boundary.

### Legacy INDEX_ONLY value

`INDEX_ONLY` is not a selectable user-facing mode.

It may remain in code only as a legacy database value when required for migration compatibility.

Legacy `INDEX_ONLY` records must be migrated to `ON_DEMAND`.

## Platform and protocol

- Pixel 9a running current stock Android is the primary target.
- Root access is not required.
- Minimum supported Android version is Android 10.
- SMB2 and SMB3 are supported through SMBJ.
- SMB1 is not supported.
- The application contains one Android app module.
- Kotlin, Compose, Hilt, Room, WorkManager, coroutines, and SMBJ are used.
- Gradle Wrapper binaries are human-managed and are not generated or modified as part of application changes.

## Connection model

A connection contains:

- stable connection ID,
- display name,
- SMB host,
- port,
- share,
- connection-root-relative base path,
- username,
- protected password,
- optional domain,
- root mode,
- creation timestamp.

Connection IDs, not display names, separate local identities.

Passwords:

- are encrypted using an Android Keystore-backed key,
- are stored outside Room,
- are never logged,
- are never displayed again after saving,
- are never included in Worker input data,
- are never included in copy history,
- are cleared from temporary in-memory character arrays where practical.

## Remote paths

All remote paths are connection-root-relative.

Every remote path must be normalized before use.

Reject:

- absolute paths,
- paths beginning with `/`,
- paths beginning with `\`,
- `..`,
- NUL,
- paths that escape the configured root,
- arbitrary share changes encoded into a path,
- the reserved backup tree as a normal Copy to SMB destination.

Path joining must not allow a child name to introduce another path component.

The selected connection determines:

- host,
- port,
- share,
- base path,
- credential,
- allowed root.

No feature may accept an arbitrary unrestricted SMB write path.

## SMB boundaries

### Read-only SmbClient

Indexing, remote folder selection, On-demand, and Keep offline use a read-only `SmbClient`.

The read-only interface may provide operations required for:

- listing directories,
- inspecting remote metadata,
- opening remote files for reading.

It must not expose:

- create,
- write,
- upload,
- rename,
- move,
- delete.

### Scoped SmbCopyClient

Copy to SMB uses a separate `SmbCopyClient`.

`SmbCopyClient` may expose only operations needed to perform a safe Copy to SMB operation:

- test destination existence,
- create destination directories below the configured connection root,
- create a unique application-owned `.part` upload,
- write a complete file into that `.part` upload,
- read an application-owned `.part` upload for verification,
- rename or move a verified `.part` upload to its final name,
- move an existing conflicting destination into the reserved backup tree,
- remove an incomplete or failed application-owned `.part` upload,
- remove a newly promoted upload only when post-promotion verification failed and restoration of the backed-up file requires it.

`SmbCopyClient` must not become a general-purpose SMB mutation API.

It must not:

- propagate deletion,
- delete an unrelated normal SMB file,
- modify a path outside the configured connection root,
- move a file outside the configured destination and reserved backup workflow,
- accept arbitrary absolute paths,
- be injected into indexing, On-demand, or Keep offline repositories or workers.

## Indexing and scanning

A scan recursively lists SMB metadata below the configured `basePath`.

Indexed entries contain:

- connection ID,
- relative path,
- parent path,
- name,
- directory flag,
- `Long` size,
- `Long` modification time,
- optional MIME type,
- effective inherited mode,
- remote existence state,
- last-seen timestamp,
- last-seen scan ID.

Scan requirements:

- The previous index must not be cleared before a scan starts.
- Safely observed entries may be incrementally upserted.
- Entries not seen by the current run are marked missing only after a completely successful scan.
- Failed, timed-out, offline, or cancelled scans preserve the previous index except for safely upserted observations.
- The indexed browser reads Room and does not contact SMB.
- The indexed browser pages by selected connection ID and parent path.
- Directories are sorted before files.
- Hierarchical, back, and root navigation are supported.
- `.network-storage-backup` is excluded from the normal index.
- Application-owned `.part` files are excluded from the normal index.
- A scan must not modify SMB content.

## On-demand downloads

An On-demand download uses a serialized WorkManager queue.

The user must select an Android Storage Access Framework tree for On-demand cache storage.

Download requirements:

1. Normalize and validate the remote relative path.
2. Open the remote file through the read-only SMB boundary.
3. Create a local `.part` document.
4. Copy through a bounded buffer.
5. Count copied bytes using `Long`.
6. Support cancellation.
7. Verify the complete expected size.
8. Promote the `.part` document to the completed name only after verification.
9. Record completed cache metadata only after promotion.
10. Remove or invalidate the `.part` document on failure or cancellation.
11. Never expose the `.part` document through the browser or external open action.

A completed document URI is opened through `ACTION_VIEW` with read permission only.

The application must not grant write permission to the external viewer.

Cache identity includes at least:

- connection ID,
- relative path,
- local document URI,
- local size,
- remote size,
- remote modification time,
- state,
- last-accessed timestamp,
- creation timestamp,
- update timestamp.

A remote size or modification-time change invalidates the prior cache entry and produces a remote-updated state.

## Keep offline copies

The user selects a separate Android Storage Access Framework tree for Keep offline data.

Keep offline storage and On-demand cache storage must not be:

- the same tree,
- parent and child trees,
- overlapping trees.

Keep offline update requirements:

1. Refresh or use a valid durable SMB index.
2. Compare indexed remote files with retained local files.
3. Copy SMB-only and SMB-newer files.
4. Write through a local `.part` document.
5. Count copied bytes using `Long`.
6. Verify expected size.
7. Promote only after complete verification.
8. Clean up `.part` on failure or cancellation.
9. Never automatically delete a local-only retained file.
10. Never upload a local retained file to SMB.

Keep offline data is not On-demand cache and must not be removed by On-demand cache eviction.

## Copy to SMB rules

A Copy to SMB rule is associated with one connection ID.

A rule contains:

- connection ID,
- persisted SAF source tree URI,
- connection-root-relative destination path,
- include-subfolders flag,
- file-name conflict policy,
- automatic-copy enabled flag,
- network policy,
- charging requirement,
- battery-not-low requirement,
- storage-not-low requirement,
- periodic interval in minutes as `Long`,
- creation timestamp,
- update timestamp.

Only supported periodic intervals may be stored.

Initial supported intervals are:

- 15 minutes,
- 60 minutes,
- 360 minutes,
- 1440 minutes.

The application must explain that Android determines the exact WorkManager execution time.

## Copy to SMB source tree

The source tree is selected using Android Storage Access Framework.

Requirements:

- Use `OpenDocumentTree`.
- Persist read access to the selected tree.
- Do not persist unnecessary write access.
- Do not accept an arbitrary raw filesystem path.
- Do not allow the source tree to overlap On-demand cache storage.
- Do not allow the source tree to overlap Keep offline storage.
- Recursively traverse subfolders only when the rule enables it.
- Preserve source-relative folder structure below the SMB destination.
- Do not follow a path outside the selected SAF tree.
- Do not modify source files.
- Do not delete source files after successful upload.
- If a source disappears during processing, fail or skip that file safely without changing an existing SMB destination.

## Copy to SMB destination

The destination is relative to the connection root.

Requirements:

- Normalize the destination before saving and before use.
- Reject root escape.
- Reject the reserved `.network-storage-backup` path as a normal destination.
- Create missing destination directories only below the connection root.
- Never accept a different host or share through the destination value.
- Never use display names as destination identity.

## File identity and hashing

Copy to SMB uses SHA-256 for content identity.

Requirements:

- Same file name is not proof of equality.
- Same size is not proof of equality.
- Same modification time is not proof of equality.
- Equal SHA-256 content is treated as identical.
- SHA-256 is computed with bounded reads.
- A complete file must not be loaded into memory.
- Byte counts and file sizes use `Long`.

Size, modification time, and prior verified history may be used as optimization hints, but must not cause different content to be treated as verified equal.

If an equal file already exists at the original destination:

- do not create another copy,
- record the file as unchanged or skipped.

If Keep both previously created a numbered file whose SHA-256 equals the source:

- reuse that result,
- do not create another numbered duplicate during every automatic run.

## Safe Copy to SMB upload

Every Copy to SMB upload follows this sequence:

1. Validate the rule and connection.
2. Normalize the destination relative path.
3. Verify that the destination remains below the configured connection root.
4. Create required destination directories.
5. Create a unique application-owned `.part` file.
6. Open the SAF source for reading.
7. Copy through a bounded buffer.
8. Check coroutine and Worker cancellation during copying.
9. Count copied bytes using `Long`.
10. Reject a copy that exceeds the expected source size.
11. Verify that final copied bytes equal the expected source size.
12. Compute the source SHA-256 while reading.
13. Read and verify the uploaded `.part` SHA-256.
14. Resolve the final-name conflict policy.
15. Promote only the verified `.part` upload.
16. Verify the promoted destination where practical.
17. Record success only after successful promotion and verification.
18. Remove the application-owned `.part` upload on failure or cancellation.

A `.part` upload must never be:

- returned as a completed result,
- indexed as a normal user file,
- opened through On-demand,
- copied through Keep offline,
- shown as a completed Copy to SMB item.

Transfer resume is not included. Interrupted uploads restart from the beginning.

## Copy to SMB conflict policies

Initial conflict policies are:

- `KEEP_BOTH`,
- `REPLACE_WITH_BACKUP`.

### KEEP_BOTH

Keep both is the default policy.

If the original destination exists and has different SHA-256 content:

- retain the original SMB file unchanged,
- assign a Windows-style numbered name to the new upload,
- insert ` (n)` before the final extension,
- start at `1`,
- choose the first available name,
- recheck availability immediately before promotion.

Examples:

- `photo.jpg` becomes `photo (1).jpg`,
- `archive.tar.gz` becomes `archive.tar (1).gz`,
- `README` becomes `README (1)`,
- `.nomedia` becomes `.nomedia (1)`.

If a numbered candidate already exists with equal SHA-256 content:

- treat it as the prior successful result,
- do not create another duplicate.

The application must not overwrite a numbered destination created concurrently by another SMB client.

### REPLACE_WITH_BACKUP

Replace with backup replaces the normal destination path without discarding the previous content.

Sequence:

1. Upload the new source into a unique `.part` file.
2. Verify complete size.
3. Verify SHA-256.
4. Create a unique backup directory.
5. Move the existing destination into the backup directory.
6. Preserve the original destination-relative path below the backup directory.
7. Promote the verified `.part` file to the original destination.
8. Verify the promoted destination.
9. Record the backup-relative path and success result.

Backup path format:

`.network-storage-backup/<UTC timestamp>_<operation ID>/<original relative path>`

Backup requirements:

- Backups are never deleted automatically.
- Backups are excluded from normal indexing.
- Backups are excluded from Keep offline copying.
- Backups are excluded from Copy to SMB traversal.
- The reserved backup tree cannot be selected as a normal destination.
- A failed backup move leaves the existing destination unchanged.
- A promotion failure must restore the backed-up destination where possible.
- A post-promotion hash failure must remove only the application's failed promoted upload and restore the backed-up destination where possible.
- A restoration failure must be recorded with a safe stable error code.
- Raw SMB exception messages must not be displayed.

## No deletion propagation

No mode propagates deletion.

Specifically:

- Deleting a device source file does not delete the SMB copy.
- Deleting an SMB file does not delete the device source.
- Deleting a retained Keep offline file does not delete the SMB file.
- Deleting an SMB file does not automatically delete the retained Keep offline copy.
- Copy to SMB does not infer deletion intent from an absent source.
- Keep offline does not infer local deletion intent as an SMB deletion request.
- Network Storage does not automatically remove device source files after upload.

Application-owned failed `.part` cleanup is not deletion propagation.

Removing a newly promoted failed upload solely to restore its backed-up predecessor is not deletion propagation.

Any future device-space cleanup feature must be separately specified and must require explicit user-visible semantics.

## Automatic Copy to SMB

Automatic Copy to SMB uses WorkManager.

User-selectable constraints include:

- automatic copy enabled or disabled,
- any connected network or unmetered network only,
- charging required,
- battery not low,
- storage not low,
- supported periodic interval.

Requirements:

- Work for the same rule is serialized.
- Manual and automatic execution for the same rule must not copy concurrently.
- Independent file failures do not prevent other safe files from being attempted.
- Cancellation stops at a safe boundary.
- A cancelled operation does not expose a partial destination.
- Network loss produces a safe retryable result where appropriate.
- Exact execution time is controlled by Android and is not guaranteed.
- Do not implement arbitrary background polling outside WorkManager.
- Do not implement exact schedules.
- Do not implement SSID-specific behavior unless separately specified.
- Do not add VPN behavior.

Long-running work uses foreground execution and user-visible progress when required by Android.

Notifications:

- do not include credentials,
- do not include raw backend exception messages,
- do not expose file content,
- may display a safe file name or relative path,
- summarize completed, unchanged, and failed counts.

## Copy to SMB history

Copy to SMB stores durable file-level results in Room.

A result contains at least:

- operation ID,
- connection ID,
- source-relative path,
- destination-relative path,
- source size as `Long`,
- verified SHA-256 when available,
- safe status,
- safe error code when applicable,
- completion timestamp.

A replace-with-backup result should also store the backup-relative path when available.

Suggested statuses include:

- `SUCCEEDED`,
- `SKIPPED_IDENTICAL`,
- `FAILED`,
- `CANCELLED`.

History requirements:

- Do not store passwords.
- Do not store credential objects.
- Do not store raw backend exception text.
- Do not store file content.
- Display only safe statuses and safe error categories.
- Connection deletion removes its local rule and history through Room foreign-key cascade.
- Connection deletion does not modify SMB or device files.

## Work serialization

The following must not run concurrently for the same logical target:

- two manual Copy to SMB operations for the same rule,
- manual and automatic Copy to SMB for the same rule,
- two promotions to the same final destination.

Use WorkManager unique work and an in-process serialization mechanism where appropriate.

Do not rely only on a prior destination existence check. Another SMB client may create the destination before promotion.

Recheck and handle the final destination safely.

## Local storage separation

The following lifecycles are distinct:

- Room metadata index,
- On-demand evictable cache,
- Keep offline retained files,
- user-selected Copy to SMB source files,
- SMB `.network-storage-backup` retained files,
- application-owned local or remote `.part` files.

Never conflate these lifecycles.

In particular:

- On-demand eviction must not delete Keep offline files.
- On-demand eviction must not delete Copy to SMB source files.
- Keep offline cleanup must not delete Copy to SMB source files.
- Copy to SMB must not upload the app's On-demand cache or Keep offline tree.
- Copy to SMB history is metadata, not proof that a device source may be automatically deleted.
- SMB backup files are retained data and are never automatically deleted.

## Errors and security

Reject unsafe paths before SMB access.

Map backend and storage failures to stable categories such as:

- `AUTHENTICATION`,
- `HOST_NOT_FOUND`,
- `SHARE_NOT_FOUND`,
- `CONNECTION`,
- `TIMEOUT`,
- `REMOTE_NOT_FOUND`,
- `LOCAL_STORAGE`,
- `LOCAL_WRITE`,
- `REMOTE_WRITE`,
- `SIZE_MISMATCH`,
- `HASH_MISMATCH`,
- `SOURCE_UNAVAILABLE`,
- `DESTINATION_CONFLICT`,
- `BACKUP_FAILED`,
- `PROMOTION_FAILED`,
- `RESTORE_FAILED`,
- `CANCELLED`,
- `UNKNOWN`.

Requirements:

- Do not expose raw backend messages.
- Do not include plaintext credentials in exceptions intended for UI.
- Do not log plaintext credentials.
- Do not persist plaintext credentials in Room, DataStore, Worker input, or history.
- Use safe user-facing messages.
- Clear temporary password character arrays where practical.
- Use `Long` for all sizes and byte accounting.

## Database and migration requirements

Room schema changes require an explicit migration.

Copy to SMB adds durable storage for:

- copy rules,
- copy history.

Migration requirements:

- Preserve existing connections.
- Preserve existing index entries.
- Preserve existing scan history.
- Preserve existing cache records.
- Preserve existing Keep offline settings.
- Maintain connection foreign-key cascades.
- Export and review the Room schema.
- Do not use destructive migration for user data.

Legacy `INDEX_ONLY` values must remain migrated to `ON_DEMAND`.

## Presentation requirements

The UI remains English.

Connection mode labels:

- `ON-DEMAND`,
- `KEEP OFFLINE`,
- `COPY TO SMB`.

Copy to SMB configuration includes:

- `Source folder`,
- `Destination below SMB folder`,
- `Include subfolders`,
- `File name conflicts`,
- `Keep both`,
- `Replace with backup`,
- `Automatic copy`,
- `Unmetered networks only`,
- `Only while charging`,
- `Battery not low`,
- `Storage not low`,
- `Schedule`,
- `Save settings`,
- `Copy now`,
- `Recent activity`.

The UI must explain:

- source files are not deleted,
- SMB files are not deleted through synchronization,
- Replace with backup retains the previous SMB content,
- WorkManager does not guarantee exact execution time,
- failed or incomplete files are not exposed as completed files.

Do not display:

- plaintext passwords,
- raw backend messages,
- unrestricted local paths,
- unrestricted SMB paths outside the configured connection.

## Test requirements

Add pure-logic and boundary tests for every new behavior.

At minimum, test:

### Paths

- normal relative destination,
- empty destination meaning connection root,
- absolute path rejection,
- `..` rejection,
- NUL rejection,
- root escape rejection,
- reserved backup destination rejection,
- child names containing separators.

### Name conflicts

- `photo.jpg` to `photo (1).jpg`,
- final-extension behavior for `archive.tar.gz`,
- extensionless files,
- dot files,
- first available number,
- concurrent destination creation,
- exhausted numbered-name range,
- equal numbered file reuse.

### Identity and transfer

- equal SHA-256 skipping,
- different same-name content,
- size mismatch,
- hash mismatch,
- sizes larger than 2 GiB represented as `Long`,
- bounded buffer copying,
- cancellation during upload,
- timeout,
- network failure,
- authentication failure.

### Safe promotion and backup

- no promotion before verification,
- `.part` cleanup on failure,
- `.part` cleanup on cancellation,
- backup before replace,
- backup move failure leaves original unchanged,
- promotion failure restores backup where possible,
- post-promotion verification failure restores backup where possible,
- backup paths preserve original relative paths,
- backups are not automatically deleted.

### No deletion propagation

- missing source retains SMB file,
- missing SMB file retains device source,
- source deletion does not invoke remote deletion,
- remote deletion does not invoke local deletion,
- successful upload does not delete source.

### WorkManager

- connected network constraint,
- unmetered network constraint,
- charging constraint,
- battery-not-low constraint,
- storage-not-low constraint,
- supported interval validation,
- automatic work cancellation when disabled,
- serialization of manual and periodic work.

### Database

- migration succeeds,
- existing data remains,
- Copy to SMB rule persists,
- history persists safe values,
- connection deletion cascades rule and history,
- credentials and raw exceptions are absent.

### Index filtering

- backup tree is hidden,
- application-owned `.part` files are hidden,
- normal files ending in unrelated text remain visible.

Use fakes for SMB boundary tests.

Real SMB integration tests are optional and environment-dependent but should be documented when run.

## Delivery order

Implement in this order:

1. Update specification and agent rules.
2. Add pure Copy to SMB path and naming policies with tests.
3. Add Room rule/history entities and migration tests.
4. Add the separate `SmbCopyClient` boundary and fakes.
5. Implement safe `.part` upload and verification.
6. Implement Keep both.
7. Implement Replace with backup and restoration.
8. Add Copy to SMB repository orchestration.
9. Add manual foreground Worker execution.
10. Add WorkManager automatic scheduling and user-selectable constraints.
11. Add Copy to SMB configuration and history UI.
12. Add index filtering for backup and `.part` internals.
13. Update README and architecture documentation.
14. Run tests, build, lint, `git diff --check`, and `git status`.
15. Perform real-device and real-SMB acceptance testing where an environment is available.

Do not implement unrelated later features opportunistically.

## Acceptance criteria

A Copy to SMB acceptance test can:

1. Register an SMB2/SMB3 connection rooted below a test destination.
2. Select a device SAF source folder.
3. Configure a root-relative SMB destination.
4. Copy a complete photo to SMB.
5. Verify byte size and SHA-256.
6. Run again and confirm identical content is skipped.
7. Place different content at the same SMB name.
8. Use Keep both and confirm Windows-style numbering.
9. Use Replace with backup and confirm the old SMB file is retained below `.network-storage-backup`.
10. Cancel an upload and confirm no completed partial file is exposed.
11. Delete the device source and confirm the SMB copy remains.
12. Delete the SMB copy externally and confirm the device source remains.
13. Enable automatic copy with selected WorkManager constraints.
14. Confirm safe file-level history is visible.
15. Confirm credentials and raw backend errors never appear in UI, logs, Worker data, or Room.

The complete application acceptance test can:

- register an SMB root such as `NAS/Manga`,
- index thousands of entries,
- browse the Room-backed index offline,
- completely download and verify an On-demand file,
- open only completed local content,
- retain Keep offline files after remote deletion,
- evict only On-demand cache under cache pressure,
- copy a selected device file to SMB without deletion propagation.
