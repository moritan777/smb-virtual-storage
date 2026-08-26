# Testing strategy

Pure JVM tests cover remote path normalization/traversal rejection, root mode inheritance, update detection, and `Long` accounting. Room instrumentation verifies successful-scan missing detection. The SMB boundary is injectable so worker/repository tests can use a fake for recursive listings, timeout/failure, and cancellation without real credentials.

An emulator/device is required for Room instrumentation, Android Keystore behavior, WorkManager process/cancel behavior, and real SMB2/3 interoperability. Pixel 9a acceptance must verify a configured base root cannot escape, a failed/cancelled scan retains the prior index, a successful scan marks missing entries, credentials never reappear, and a roughly 1,000-entry result remains responsive.

Later phases add partial cleanup, size validation including zero and 2GB+ sizes, FileProvider intent grants, offline state, LRU/mirror exclusion, and shared-storage mirror acceptance tests.

Step 3 Room instrumentation covers root and nested parent queries, folder-first paging over more than one page, Unicode names, missing metadata, connection cascade, root-index deletion, and credential cleanup without SMB access. Settings JVM tests cover defaults, presets, custom validation, overflow, and Long arithmetic; instrumentation verifies DataStore persistence.

Step 4 tests cover bounded zero/small/50 MiB copying and mismatch, cache freshness and overflow-safe limit policy, SAF root separation/persistence, Cache metadata joins and usage, MIME/read-grant intent construction, remote-picker containment, and cancellation/failure cleanup. Device acceptance additionally verifies persisted provider permissions, process interruption, large SMB files, offline cached open, and a CBZ handler such as Perfect Viewer.

Step 4.1 tests cover add/edit state without password material, blank-password preservation versus explicit replacement, share/base-path display mapping, parent navigation, and cache-state icon presentation. Emulator acceptance covers responsive paired fields, share enumeration, share-root/nested selection, and the simplified non-technical browser rows.
