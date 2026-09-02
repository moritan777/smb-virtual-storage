# Copy to SMB V1 implementation audit

This audit tracks the current `feature/copy-decision-dry-run` implementation against `docs/v1-spec.md`. It is intentionally an implementation-status document: the V1 spec remains the product/security contract, while this file records what has actually been implemented and what still needs verification or UI work.

## Implemented

### Safety and storage boundary

- Separate scoped `SmbCopyClient` write boundary; normal SMB client remains read-only.
- SAF source-tree selection with persisted read permission.
- Destination normalization below the configured SMB root.
- Source / Cache / Keep offline overlap protection.
- `.part` upload flow with bounded copying, `Long` byte accounting, size verification, SHA-256 verification, promotion, and cleanup.
- `KEEP_BOTH` and `REPLACE_WITH_BACKUP` conflict policies.
- Reserved `.network-storage-backup` workflow with restore handling.
- No source deletion, rename, move, or deletion propagation.

### Rules and execution

- Durable Room rule/history storage with bounded history retention.
- Manual and periodic WorkManager execution with constraints and startup reconciliation.
- Separate unique work streams for manual, periodic, and failed-file retry.
- Per-rule execution serialization.
- Live WorkManager progress and recent activity UI.
- User-visible manual-copy cancellation that does not disable periodic scheduling.
- Automatic-copy editor explains that WorkManager, not an exact clock schedule, determines execution timing.

### Decision planning / Preview

- Side-effect-free `CopyDecisionEngine` with `NEW`, `UNCHANGED`, `KEEP_BOTH`, `REPLACE`, and `REUSE_EXISTING` decisions.
- SHA-256-based comparison rather than filename/size/mtime alone.
- Reuse of an identical original destination.
- Reuse of an identical numbered `KEEP_BOTH` destination.
- `CopyDryRunPlanner` that reads source and destination state and produces a file-level plan without SMB writes or deletions.
- Preview UI showing aggregate counts and per-file decisions/reasons.

### Retry and selective execution

- Scheduler accepts a collection of failed source-relative paths for retry.
- Worker/executor can restrict execution to selected relative paths.
- Retry work is separate from the normal manual and periodic work streams.
- ViewModel can collect failed history entries and enqueue only those paths.

### Browser cache visibility

- Browser folder state can be marked when any descendant file has a physical cached entry.
- The marker is inherited through arbitrary descendant depth by the Room browser projection.
- The marker is presentation guidance only; it does not claim that every file in the folder is cached.

## Verification status

The current implementation has passed the existing JVM/instrumentation test suite used during this development cycle, including a run of 15 connected Android tests. Build/install verification is also performed with `assembleDebug` and `installDebug` on an Android emulator.

Real-NAS verification remains necessary for the SMB-specific cases below.

## Remaining verification / hardening

1. **Real SMB conflict verification**
   Exercise `KEEP_BOTH` and `REPLACE_WITH_BACKUP` against a real SMB target, including identical-content reuse, numbered-name collision, backup creation, promotion failure, and restore behavior where practical.

2. **Cancellation during active upload**
   Cancel a multi-file manual upload while a file is transferring and confirm that the in-flight `.part` is removed, history records `CANCELLED`, completed files remain completed, and automatic scheduling remains enabled.

3. **Retry end-to-end verification**
   Create a real failed history entry, invoke the failed-file retry path, and confirm that only those source-relative paths are executed.

4. **Folder marker device verification**
   Confirm that a folder containing a cached descendant shows the local-data marker, that an ancestor also shows it, and that a folder with no cached descendants does not. Confirm the marker is not interpreted as full-folder offline availability.

5. **Large-file / long-transfer verification**
   Run large Copy to SMB transfers to validate `Long` byte accounting, bounded memory use, cancellation responsiveness, foreground execution, and post-upload hash verification under realistic network conditions.

6. **Preview versus execution race**
   Change a destination between Preview and actual copy and confirm that execution re-evaluates the destination rather than trusting stale Preview results.

These are verification/hardening items; no broad SMB write capability or deletion propagation should be introduced while closing them.
