# V1 implementation addendum

This document supplements `docs/v1-spec.md` for the current `feature/copy-decision-dry-run` implementation. The V1 specification remains the normative product and security contract; this addendum records implemented behavior that was added after the original V1 specification was written.

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

Retry is a separate unique WorkManager stream from manual and periodic execution. It does not broaden the SMB write boundary and it does not delete or modify source files.

The current ViewModel exposes the failed-file retry operation to the application layer. The current Copy to SMB card UI exposes Preview, Activity, Copy now, Cancel, Edit, and Delete; a dedicated failed-only retry button is not yet exposed in that card.

## Folder cache marker

The browser projection now marks a directory as having local cached content when any descendant file at any depth has a physical `CACHED` cache entry.

The marker is intentionally not a folder-level cache claim. It means only that some content exists locally below the folder and helps the user decide where to navigate when offline.

## Current documentation boundary

- `docs/v1-spec.md` — normative V1 product/security contract.
- `docs/v1-implementation-addendum.md` — current implementation extensions described above.
- `docs/architecture.*.md` — implementation boundaries and component responsibilities.
- `docs/testing.*.md` — current automated and device verification strategy.
- `docs/copy-to-smb-v1-audit.md` — implementation status and remaining hardening work.
