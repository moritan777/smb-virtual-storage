package dev.networkstorage.data.smb

import dev.networkstorage.domain.ConnectionConfig
import dev.networkstorage.domain.Credential
import java.io.Closeable
import java.io.OutputStream
import kotlin.ConsistentCopyVisibility

/**
 * Application-owned temporary upload created for one Copy to SMB operation.
 *
 * Callers receive this token from [SmbCopyClient.createPart] rather than supplying an
 * arbitrary path to cleanup APIs. This keeps partial-upload deletion scoped to files
 * created by the same application operation.
 */
@ConsistentCopyVisibility
data class AppOwnedPart internal constructor(
    val relativePath: String,
    val operationId: String,
)

/**
 * Newly promoted upload that may be removed only as part of post-promotion rollback.
 */
@ConsistentCopyVisibility
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
    suspend fun exists(connection: ConnectionConfig, credential: Credential, relativePath: String): Boolean
    suspend fun createDirectories(connection: ConnectionConfig, credential: Credential, relativeDirectory: String)
    suspend fun createPart(connection: ConnectionConfig, credential: Credential, destinationDirectory: String, originalFileName: String, operationId: String): RemotePartWriteHandle
    suspend fun openPartRead(connection: ConnectionConfig, credential: Credential, part: AppOwnedPart): RemoteReadHandle
    suspend fun promotePart(connection: ConnectionConfig, credential: Credential, part: AppOwnedPart, finalRelativePath: String): PromotedUpload
    suspend fun moveToBackup(connection: ConnectionConfig, credential: Credential, existingRelativePath: String, backupRelativePath: String)
    suspend fun restoreBackup(connection: ConnectionConfig, credential: Credential, backupRelativePath: String, originalRelativePath: String)
    suspend fun removePart(connection: ConnectionConfig, credential: Credential, part: AppOwnedPart)
    suspend fun removePromotedForRestore(connection: ConnectionConfig, credential: Credential, promoted: PromotedUpload)
}
