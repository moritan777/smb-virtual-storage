package dev.networkstorage.data.smb

import dev.networkstorage.domain.ConnectionConfig
import dev.networkstorage.domain.Credential
import java.io.Closeable
import java.io.OutputStream

/**
 * Application-owned temporary upload created for one Copy to SMB operation.
 *
 * Callers receive this token from [SmbCopyClient.createPart] rather than supplying an
 * arbitrary path to cleanup APIs. This keeps partial-upload deletion scoped to files
 * created by the same application operation.
 */
data class AppOwnedPart internal constructor(
    val relativePath: String,
    val operationId: String,
)

/**
 * Newly promoted upload that may be removed only as part of post-promotion rollback.
 */
data class PromotedUpload internal constructor(
    val relativePath: String,
    val operationId: String,
)

interface RemotePartWriteHandle : Closeable {
    val part: AppOwnedPart
    val output: OutputStream
}

/**
 * Narrow write boundary used only by Copy to SMB.
 *
 * This is intentionally separate from [SmbClient]. Indexing, remote browsing,
 * On-demand and Keep offline must continue to depend only on the read-only boundary.
 * Implementations must normalize every supplied relative path against the configured
 * connection root and reject absolute paths, traversal, NUL and root escape.
 */
interface SmbCopyClient {
    /** Tests whether a connection-root-relative destination exists. */
    suspend fun exists(
        connection: ConnectionConfig,
        credential: Credential,
        relativePath: String,
    ): Boolean

    /** Creates a destination directory tree below the configured connection root. */
    suspend fun createDirectories(
        connection: ConnectionConfig,
        credential: Credential,
        relativeDirectory: String,
    )

    /**
     * Creates a unique application-owned `.part` file below [destinationDirectory]
     * and opens it for complete-file writing.
     */
    suspend fun createPart(
        connection: ConnectionConfig,
        credential: Credential,
        destinationDirectory: String,
        originalFileName: String,
        operationId: String,
    ): RemotePartWriteHandle

    /** Opens only an application-owned `.part` upload for verification reads. */
    suspend fun openPartRead(
        connection: ConnectionConfig,
        credential: Credential,
        part: AppOwnedPart,
    ): RemoteReadHandle

    /**
     * Promotes a verified application-owned `.part` upload to a final destination.
     * Implementations must not overwrite a concurrently created destination.
     */
    suspend fun promotePart(
        connection: ConnectionConfig,
        credential: Credential,
        part: AppOwnedPart,
        finalRelativePath: String,
    ): PromotedUpload

    /**
     * Moves an existing conflicting destination into the reserved backup workflow.
     * Both paths remain connection-root-relative; [backupRelativePath] must be inside
     * `.network-storage-backup`.
     */
    suspend fun moveToBackup(
        connection: ConnectionConfig,
        credential: Credential,
        existingRelativePath: String,
        backupRelativePath: String,
    )

    /**
     * Restores one file from the reserved backup tree to its original destination.
     * This operation exists only for Replace-with-backup rollback and must never
     * overwrite a path that appeared concurrently.
     */
    suspend fun restoreBackup(
        connection: ConnectionConfig,
        credential: Credential,
        backupRelativePath: String,
        originalRelativePath: String,
    )

    /** Removes only an incomplete or failed application-owned `.part` upload. */
    suspend fun removePart(
        connection: ConnectionConfig,
        credential: Credential,
        part: AppOwnedPart,
    )

    /**
     * Removes only a newly promoted upload while restoring a backed-up destination
     * after post-promotion verification failure.
     */
    suspend fun removePromotedForRestore(
        connection: ConnectionConfig,
        credential: Credential,
        promoted: PromotedUpload,
    )
}
