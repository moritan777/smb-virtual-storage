# Testing strategy

Pure JVM tests cover remote path normalization/traversal rejection, root mode inheritance, update detection, and `Long` accounting. Room instrumentation verifies successful-scan missing detection. The SMB boundary is injectable so worker/repository tests can use a fake for recursive listings, timeout/failure, and cancellation without real credentials.

An emulator/device is required for Room instrumentation, Android Keystore behavior, WorkManager process/cancel behavior, and real SMB2/3 interoperability. Pixel 9a acceptance must verify a configured base root cannot escape, a failed/cancelled scan retains the prior index, a successful scan marks missing entries, credentials never reappear, and a roughly 1,000-entry result remains responsive.

Later phases add partial cleanup, size validation including zero and 2GB+ sizes, FileProvider intent grants, offline state, LRU/mirror exclusion, and shared-storage mirror acceptance tests.

Step 3 Room instrumentation covers root and nested parent queries, folder-first paging over more than one page, Unicode names, missing metadata, connection cascade, root-index deletion, and credential cleanup without SMB access. Settings JVM tests cover defaults, presets, custom validation, overflow, and Long arithmetic; instrumentation verifies DataStore persistence.
