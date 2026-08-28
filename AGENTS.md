# Network Storage agent rules

## Goal and source of truth

Build the non-root Android app specified by `docs/v1-spec.md`.

The app indexes a user-selected SMB2/SMB3 subtree and supports:

- complete-file On-demand downloads,
- retained one-way SMB-to-device offline copies,
- scoped device-to-SMB copies through Copy to SMB.

The specification is authoritative. Do not silently change it. If implementation requirements conflict with `docs/v1-spec.md`, update the specification explicitly in the same change before implementing the new behavior.

## Product modes

Keep the user-facing modes and their responsibilities distinct.

- `ON_DEMAND`
  - Index SMB metadata.
  - Download a complete file only when requested.
  - Store downloaded content in an evictable local cache.
  - SMB access is read-only.

- `MIRROR` / Keep offline
  - Index SMB metadata.
  - Retain complete local copies for offline access.
  - Copy direction is SMB to device only.
  - SMB access is read-only.
  - Do not propagate deletions in either direction.

- `COPY_TO_SMB` / Copy to SMB
  - Copy files from a user-selected Android Storage Access Framework source tree to a configured SMB destination below the connection root.
  - Support manual copying and optional WorkManager automatic copying.
  - Never delete the source file from the device.
  - Never propagate deletion from either endpoint.
  - Never treat this feature as bidirectional synchronization.

`INDEX_ONLY` may remain only as a legacy database value when required for migration compatibility. Do not expose it as a selectable user-facing mode unless the specification explicitly restores it.

## SMB access boundaries

### Read-only boundary

Indexing, remote folder selection, On-demand, and Keep offline must use the read-only SMB boundary.

The read-only SMB interface may expose only operations required to:

- list shares or directories,
- inspect remote metadata,
- open a remote file for reading.

Do not add create, write, rename, move, or delete operations to the read-only interface.

### Copy to SMB boundary

Copy to SMB must use a separate, narrowly scoped SMB write boundary, such as `SmbCopyClient`.

The Copy to SMB boundary may perform only the following operations:

- check whether a destination file or directory exists,
- create destination directories below the configured connection root,
- create a uniquely named `.part` upload file below the configured destination,
- write the complete device file into that `.part` file,
- read the uploaded `.part` file when needed for verification,
- rename or move a successfully verified `.part` file to its final destination,
- move an existing conflicting destination file into the reserved backup tree before replacement,
- remove only an incomplete or failed `.part` file created by the same application operation,
- remove a newly promoted upload only when promotion verification fails and restoring the backed-up destination requires it.

Do not expose a generic arbitrary remote mutation API.

The Copy to SMB boundary must never:

- delete a normal user file as deletion propagation,
- delete an existing destination without first retaining it when the selected policy is Replace with backup,
- rename or move a remote file outside the configured destination or reserved backup workflow,
- write outside the configured connection root,
- accept an absolute remote path,
- accept `..`, NUL, or any path that escapes the configured root,
- modify SMB data from INDEX, On-demand, or Keep offline code,
- implement bidirectional synchronization.

## Copy to SMB behavior

A Copy to SMB rule contains at least:

- connection ID,
- persisted SAF source tree URI,
- connection-root-relative SMB destination path,
- whether to include subfolders,
- file-name conflict policy,
- whether automatic copying is enabled,
- network constraint,
- charging constraint,
- battery-not-low constraint,
- storage-not-low constraint,
- periodic interval,
- creation and update timestamps.

Use connection IDs, not display names, to separate rules and history.

### Source folder

- The user must select the source folder through Android Storage Access Framework.
- Persist only the granted content URI, never an arbitrary raw filesystem path.
- Retain only the permissions required to read the selected source tree.
- Do not allow the Copy to SMB source tree to overlap the app's On-demand cache or Keep offline storage.
- Preserve the selected source tree's relative folder structure below the configured SMB destination.
- Never delete, rename, or move source files on the device.

### Destination folder

- The SMB destination must be connection-root-relative.
- Normalize every destination path before use.
- Reject absolute paths, `..`, NUL, and root escape.
- Do not allow the reserved backup tree to be selected as a normal destination.
- Do not accept an arbitrary SMB share or host through a path string.
- The configured connection determines the host, share, base path, credential, and write boundary.

### File identity

- Use SHA-256 to determine whether same-path files have identical content.
- Do not treat file name, file size, or modification time alone as proof of equality.
- Size and modification time may be used only to avoid unnecessary hashing when a previously verified history record safely permits it.
- Hash files with bounded streaming reads; never load a complete file into memory.
- Use `Long` for sizes, transferred bytes, progress, and storage accounting.

### Safe upload

Every upload must:

1. normalize and validate the destination,
2. create required destination directories below the configured root,
3. create a unique `.part` file,
4. copy with a bounded buffer,
5. support coroutine and Worker cancellation,
6. count transferred bytes with `Long`,
7. verify the complete expected size,
8. calculate and verify SHA-256,
9. resolve any final-name conflict according to the configured policy,
10. promote the verified `.part` file only after all required checks pass,
11. record success only after promotion and final verification,
12. clean up its own incomplete `.part` file on failure or cancellation.

Never expose or index a partial upload as a normal file.

Transfer resume is not part of the initial Copy to SMB implementation. An interrupted upload may restart from the beginning.

### Equal content

If the destination path exists and its SHA-256 equals the source SHA-256:

- do not upload or replace it again,
- record it as unchanged or skipped,
- do not create a numbered duplicate.

When Keep both previously created a numbered destination with identical content, reuse that verified result instead of creating another numbered copy during every automatic run.

### Keep both

When the destination name exists with different content and the rule uses Keep both:

- keep the existing SMB file unchanged,
- rename the newly uploaded file using Windows-style numbering,
- insert ` (n)` before the final extension,
- start at `1`,
- use the first available name.

Examples:

- `photo.jpg` → `photo (1).jpg`
- `archive.tar.gz` → `archive.tar (1).gz`
- `README` → `README (1)`
- `.nomedia` → `.nomedia (1)`

Recheck destination availability immediately before promotion. Do not overwrite a file created concurrently by another client.

Keep both should be the default conflict policy.

### Replace with backup

When the destination exists with different content and the rule uses Replace with backup:

1. completely upload and verify the new `.part` file,
2. create a unique backup directory,
3. move the existing destination file into the backup directory while preserving its relative path,
4. promote the verified `.part` file to the original destination,
5. verify the promoted file,
6. record the backup path and successful result.

Use a backup path shaped like:

`.network-storage-backup/<UTC timestamp>_<operation ID>/<original relative path>`

Requirements:

- never automatically delete backup files,
- exclude the backup tree from normal indexing and Copy to SMB source/destination traversal,
- do not copy the backup tree to the device through Keep offline,
- do not open it as a normal user file through On-demand,
- if backup movement fails, leave the original destination unchanged and fail that file,
- if promotion or post-promotion verification fails, restore the backed-up destination where possible,
- never expose backend exception text directly in the UI.

### Deletion behavior

Copy to SMB does not propagate deletion.

- If a device source file is deleted, retain the SMB copy.
- If an SMB file is deleted externally, do not delete any device source file.
- Do not infer deletion intent from a missing file.
- Do not implement SMB-to-device deletion.
- Do not implement device-to-SMB deletion.
- Do not automatically delete device files after successful upload.

A future user-directed "free device space" workflow, if ever added, must be a separate explicitly specified feature and must not be implemented opportunistically.

## Automatic Copy to SMB

Use WorkManager for automatic Copy to SMB execution.

Allow the user to configure:

- automatic copying on or off,
- connected network or unmetered network only,
- charging required,
- battery not low,
- storage not low,
- supported periodic interval,
- whether subfolders are included,
- conflict policy.

Requirements:

- serialize work for the same Copy to SMB rule,
- do not run manual and periodic work concurrently for the same rule,
- do not promise exact execution times,
- continue processing independent files when one file fails,
- stop safely on cancellation,
- never leave a partial file visible,
- persist a safe result for each attempted file,
- notify the user when failures require attention without exposing credentials, source contents, or raw backend messages.

Do not implement exact scheduling, background polling outside WorkManager, VPN behavior, or arbitrary SSID-based execution unless separately specified.

## Copy history and errors

Persist a bounded or specified Copy to SMB history in Room.

A file-level history record may contain:

- operation ID,
- connection ID,
- source-relative path,
- destination-relative path,
- source size as `Long`,
- verified SHA-256,
- safe status,
- safe error code,
- backup-relative path when applicable,
- completion timestamp.

Never persist:

- plaintext passwords,
- credential objects,
- raw backend exception messages,
- arbitrary local filesystem paths,
- file contents.

Map failures to stable safe categories. UI and notifications must display only user-safe messages or codes.

## General invariants

- Do not add a `DocumentsProvider`.
- Do not add FUSE or root mounts.
- Do not add partial-file open.
- Do not add thumbnails.
- Do not add VPN functionality.
- Do not add bidirectional sync.
- Do not stream a remote file directly to a viewer.
- Bounded streaming copy inside a complete-file download or Copy to SMB transfer is allowed and required.
- Keep SMB, database, credential, worker, and presentation responsibilities separated.
- Do not add unrelated frameworks or unrelated refactors.
- Remote paths must remain connection-root-relative and normalized.
- Index metadata, On-demand cache, Keep offline data, device source data, and SMB backup data are separate lifecycles. Never conflate them.
- Never log, display again, or persist plaintext credentials.
- Never put raw backend exception text directly in UI or notifications.
- Use `Long` for sizes and byte accounting.

## Scan invariants

- A scan must not delete or clear the previous index before starting.
- Upsert safely observed entries incrementally.
- Mark unseen entries missing only after a completely successful scan.
- Failed, timed-out, offline, or cancelled scans must preserve the prior index.
- Exclude `.network-storage-backup` and application-owned `.part` files from the normal index.
- The indexed browser reads Room and does not contact SMB.

## Architecture and testability

Keep these responsibilities separated:

- `SmbClient`: read-only listing and reading,
- `SmbCopyClient`: narrowly scoped Copy to SMB operations,
- repositories: copy/index/cache/Keep offline orchestration,
- Room: durable metadata, rules, and history,
- credential store: protected credentials only,
- workers: background lifecycle, constraints, progress, and cancellation,
- presentation: user-safe state and UI.

SMB implementations must remain behind interfaces so fakes can test:

- listing,
- successful upload,
- equal-hash skipping,
- Windows-style conflict naming,
- replacement with backup,
- backup failure,
- promotion failure,
- hash mismatch,
- size mismatch,
- authentication failure,
- connection failure,
- cancellation,
- timeout,
- concurrent destination creation.

Add tests for new pure logic and boundary behavior.

At minimum, Copy to SMB tests should cover:

- path normalization and root escape rejection,
- reserved backup destination rejection,
- Windows-style numbered naming,
- extensionless and dot-file names,
- equal SHA-256 skipping,
- repeated automatic runs not creating duplicate numbered files,
- `.part` cleanup after failure and cancellation,
- no final promotion after size or hash mismatch,
- backup-before-replace ordering,
- restoration after failed promotion where possible,
- no deletion propagation,
- `Long` sizes larger than 2 GiB,
- WorkManager constraint construction,
- Room migration and connection-delete cascade,
- credentials never appearing in state, logs, history, or errors.

## Scope and quality

- Implement phases in the order defined by `docs/v1-spec.md`.
- Do not implement later phases opportunistically.
- Do not perform unrelated refactors.
- Update `docs/v1-spec.md`, README documentation, and tests when adding Copy to SMB.
- Run unit tests.
- Run relevant Android instrumentation tests where an emulator or device exists.
- Run builds and lint where practical.
- Run `git diff --check`.
- Run `git status`.
- Do not claim a test passed when it was not executed.
- Clearly report environment limitations.

Codex must not generate, modify, add, or commit binary files.

Gradle Wrapper files, especially `gradle-wrapper.jar`, are human-managed and must not be touched by Codex.
