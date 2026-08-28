# Copy to SMB V1 implementation audit

This audit compares the current `main` implementation with `docs/v1-spec.md` after the Copy to SMB rule UI, WorkManager execution, progress reporting, activity history, overlap protection, and manual cancellation work.

## Implemented

- Separate scoped `SmbCopyClient` write boundary; the normal SMB client remains read-only.
- SAF source-tree selection with persisted read permission.
- Destination normalization below the configured SMB root.
- Source/cache/Keep offline overlap protection.
- `.part` upload flow with bounded copying, size verification, SHA-256 verification, promotion, and cleanup.
- `KEEP_BOTH` and `REPLACE_WITH_BACKUP` conflict policies.
- Reserved `.network-storage-backup` workflow with restore handling.
- No source deletion, rename, move, or deletion propagation.
- Durable Room rule/history storage with bounded history retention.
- Manual and periodic WorkManager execution with constraints and startup reconciliation.
- Per-rule execution serialization.
- Live WorkManager progress and recent activity UI.
- User-visible manual-copy cancellation that does not disable periodic scheduling.
- Automatic-copy editor now explains that WorkManager, not an exact clock schedule, determines execution timing.

## Remaining verification / hardening

1. **WorkInfo selection robustness**
   `CopyRulesViewModel` currently selects work from WorkManager result lists using `lastOrNull()`. Verify that list ordering is guaranteed for the intended newest-work semantics; otherwise select deterministically by generation/time/id or change the observation model.

2. **Android SAF tree-overlap instrumentation coverage**
   `TreeRelationship` depends on Android `Uri` / `DocumentsContract`. Add instrumentation coverage for same-tree, parent/child, unrelated-tree, and provider/authority cases rather than relying only on JVM tests.

3. **Cancellation device verification**
   Exercise cancellation during an active multi-file upload and confirm the in-flight `.part` is removed, history records `CANCELLED`, completed files remain completed, and an automatic rule remains scheduled.

4. **Conflict-policy device verification**
   Exercise both `KEEP_BOTH` and `REPLACE_WITH_BACKUP` against a real SMB target, including identical-content reuse, numbered-name collision, backup creation, promotion failure, and restore behavior where practical.

5. **Long-transfer / large-file verification**
   Run a large-file copy to validate `Long` byte accounting, bounded memory use, cancellation responsiveness, foreground execution, and post-upload hash verification under realistic network conditions.

These are verification/hardening items; no additional broad write capability or deletion propagation should be introduced while closing them.
