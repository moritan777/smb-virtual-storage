# V1 implementation addendum

This document supplements `docs/v1-spec.md` for implementation behavior added after the original V1 specification was written. The V1 specification remains the normative product and security contract; this addendum records implementation extensions in the current code line. Check the target branch history to determine whether each extension has been merged into the default branch.

## Copy to SMB decision planning

Copy to SMB now has a side-effect-free decision layer shared by Preview and execution diagnostics.

The decision vocabulary is:

- `NEW` — the original destination does not exist and a new upload is planned.
- `UNCHANGED` — the original destination has identical SHA-256 content and no upload is required.
- `KEEP_BOTH` — the original destination differs and a new numbered destination is planned.
- `REPLACE` — the original destination differs and `REPLACE_WITH_BACKUP` will replace it after backup.
- `REUSE_EXISTING` — a numbered `KEEP_BOTH` destination already contains identical content and can be reused.

Filename, size, and modification time are not treated as proof of content equality. SHA-256 is the content identity used by the decision engine.

## Copy to SMB Preview

The Copy to SMB UI can build a dry-run plan before execution. Preview may read the SAF source and existing SMB destination content, including hashing, but it must not create, modify, rename, promote, or delete any SMB file.

Preview reports aggregate counts and per-file destination, decision, size, and reason. Preview is not a transaction or lock: another SMB client may change the destination after Preview, so execution must re-evaluate the current destination state.

## Selective retry

The Copy to SMB scheduler and worker support retrying only selected source-relative paths. Failed history entries can be collected and passed as an `onlyRelativePaths` set to a retry work request.

Retry is a separate unique WorkManager stream from manual and daily execution. It does not broaden the SMB write boundary and it does not delete or modify source files.

The current ViewModel exposes the failed-file retry operation to the application layer. The current Copy to SMB card UI exposes Preview, Activity, Copy now, Cancel, Edit, and Delete; a dedicated failed-only retry button is not yet exposed in that card.

## Backup settings experience

A dedicated Backup area provides a focused workflow for NAS connections and phone-folder backup rules. Users can add a NAS connection by entering its host, port, credentials, and SMB share, then configure a source folder on the phone and a destination folder below the NAS connection root.

Each backup rule can be configured for daily execution at a selected local time, with a network policy (any connected network or Wi-Fi) and an optional charging requirement. WorkManager can delay execution; a run that starts while its configured conditions are not met is skipped and recorded in history. The configured time is not an exact execution-time guarantee.

Deleting a connection removes its app-side backup configuration, history, local index, and saved login. It does not delete NAS-resident files or the source files on the phone.

## NAS destination folder picker

The Backup rule editor includes a NAS destination picker. It lists folders from the selected SMB share below the configured connection root, supports navigation to a parent folder, and applies the current folder as the rule destination. The stored destination remains relative to the connection root.

The picker can create a new directory at its current location. The folder name is trimmed and must not be blank; the resulting path is normalized before it is passed to `SmbClient.createDirectory`. This is a deliberately narrow directory-creation operation, not a general file-write API. File transfers continue to use the separate `SmbCopyClient` boundary. The picker uses the selected connection's saved credential and clears the password character array after the operation.

## Test Preview stability

The Backup rule card's **Test** action opens the Copy to SMB dry-run preview. The preview screen renders loading, error, and result states as mutually exclusive content branches rather than returning early from inside its Compose layout. Preview result rows use keys that remain unique even if source-relative paths repeat. The ViewModel handles a missing connection and maps known credential, SAF source-tree, invalid or overlapping source-path, and SMB failures to actionable user-facing messages. Coroutine cancellation is rethrown instead of being treated as an ordinary preview error.

## Folder cache marker

The browser projection now marks a directory as having local cached content when any descendant file at any depth has a physical `CACHED` cache entry.

The marker is intentionally not a folder-level cache claim. It means only that some content exists locally below the folder and helps the user decide where to navigate when offline.

## Current documentation boundary

- `docs/v1-spec.md` — normative V1 product/security contract.
- `docs/v1-implementation-addendum.md` — current implementation extensions described above.
- `docs/architecture.*.md` — implementation boundaries and component responsibilities.
- `docs/testing.*.md` — current automated and device verification strategy.
- `docs/copy-to-smb-v1-audit.md` — implementation status and remaining hardening work.
