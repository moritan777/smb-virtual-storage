# Network Storage

Network Storage is a non-root Android app that keeps a local metadata index of selected SMB2/SMB3 NAS folders. It is **not** a microSD replacement or OS mount and never writes back to the NAS.

V1 separates **Index only** (metadata), **On-demand** (future evictable complete-file cache), and **Mirror** (future retained one-way copy). Completed files will later open in external apps through safe Android content URIs. A NAS-side delete may mark metadata missing but will not automatically remove a mirror file.

This repository currently implements Steps 0–4: connection registration, protected credentials, scanning, a Room/Paging indexed browser, local deletion, separate SAF Cache/Mirror destinations, and complete on-demand cached downloads opened through read-only `ACTION_VIEW` grants. Mirror copying and automatic cache eviction intentionally remain unimplemented.

Step 4.1 presents those features as a file manager: Connections is a compact management list, Add/Edit uses a separate editor, a single Network folder picker maps share and relative base path internally, and browser rows show folders or cache icons plus file size without diagnostic status text.

## Storage decision

On-demand files use the user-selected SAF Cache tree; Mirror has a different SAF tree setting and is never counted as cache. The system tree picker provides folder creation and persistent access without broad filesystem permission. Cache downloads use `.part`, bounded copying, size validation, and promotion before their document URI can be opened externally.

## Build

Use JDK 17, a human-managed Gradle installation/wrapper, and an Android SDK with API 35, then run `gradle test assembleDebug`.
