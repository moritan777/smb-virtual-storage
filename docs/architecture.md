# Steps 0–2 architecture

The single `app` module uses a small presentation/domain/data split. Compose and `MainViewModel` render connection registration, WorkManager progress/cancellation, and a bounded Room preview. `IndexRepository` owns the recursive breadth-first scan. Room owns connection metadata, root rules, indexed entries, and durable scan runs.

`SmbClient` is deliberately read-only and exposes only directory listing. `SmbjClient` selects SMBJ because it is SMB2/3-only, mature, and provides the metadata needed here; jcifs-ng remains a possible later replacement behind the interface rather than a second V1 backend. Network work runs on IO and WorkManager provides process-resilient scheduling and cancellation.

Passwords are AES-GCM encrypted with a non-exportable Android Keystore key and stored separately from Room. Remote paths are normalized at configuration and listing boundaries. A scan tags each observation with its run ID, incrementally upserts it, and marks unseen rows missing only in the same transaction that completes a successful run.

Download, local-file, mirror, cache, and external-open boundaries are intentionally absent until their specified phases.
