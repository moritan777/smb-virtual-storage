# Network Storage agent rules

## Goal and source of truth

Build the non-root Android app specified by `docs/v1-spec.md`. It indexes a user-selected SMB2/3 subtree and, in later phases, supports complete-file on-demand downloads and one-way mirrors. The specification is authoritative; do not silently change it.

## Invariants

- Remote SMB access is read-only. Never add SMB create, write, upload, rename, move, or delete APIs.
- Do not add a `DocumentsProvider`, FUSE/root mount, streaming, partial-file open, thumbnails, VPN, or bidirectional sync.
- Keep SMB, database, credential, worker, and presentation responsibilities separated without unrelated frameworks or refactors.
- Remote paths are connection-root-relative, normalized, and must never escape the configured root.
- Index-only stores metadata, on-demand files are evictable cache, and mirror files are retained data. Never conflate these lifecycles.
- A future download must use a `.part` file, bounded copying, size validation, atomic promotion where possible, and cleanup on failure/cancellation. Never expose a partial file.
- Never log, display again, or persist plaintext credentials. Do not put backend exception text directly in UI.
- Use `Long` for sizes and byte accounting.

## Scope and quality

- Implement phases in the order in `docs/v1-spec.md`; do not implement later phases opportunistically.
- Add tests for new pure logic and boundary behavior. Keep SMB behind `SmbClient` so fakes can test listing, failures, cancellation, and timeouts.
- A scan must not delete the prior index before starting. Mark unseen entries missing only after a completely successful scan.
- Do not perform unrelated refactors. Run unit tests, relevant Android tests where an emulator exists, builds, lint where practical, `git diff --check`, and `git status`.
- Codex must not generate, modify, add, or commit binary files. Gradle Wrapper files (especially `gradle-wrapper.jar`) are human-managed and must not be touched by Codex.
