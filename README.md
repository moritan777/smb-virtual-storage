# Network Storage

Network Storage is a non-root Android app that keeps a local metadata index of selected SMB2/SMB3 NAS folders. It is **not** a microSD replacement or OS mount and never writes back to the NAS.

V1 separates **Index only** (metadata), **On-demand** (future evictable complete-file cache), and **Mirror** (future retained one-way copy). Completed files will later open in external apps through safe Android content URIs. A NAS-side delete may mark metadata missing but will not automatically remove a mirror file.

This repository currently implements Steps 0–3: connection registration, protected credentials, read-only SMBJ listing, Room indexing, cancellable manual WorkManager scans, a Room/Paging indexed browser, local registration/index deletion, and a persisted On-demand cache limit. Download, external open, mirror copying, and cache eviction intentionally remain unimplemented.

## Storage decision

No file bodies are stored in the current phase. The planned on-demand cache uses app-specific external storage plus FileProvider. Mirror must be visible to non-SAF file browsers under `/storage/emulated/0/NetworkStorage/Mirror`, so its later phase will request and explain `MANAGE_EXTERNAL_STORAGE`; that policy-sensitive capability is deliberately deferred until mirror implementation.

## Build

Use JDK 17, the included Gradle 8.11.1 wrapper, and an Android SDK with API 35, then run `./gradlew test assembleDebug` (or `gradlew.bat` on Windows).
