package dev.networkstorage.data.smb

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import dev.networkstorage.domain.ConnectionConfig
import dev.networkstorage.domain.CopyDestinationPath
import dev.networkstorage.domain.Credential
import dev.networkstorage.domain.NetworkError
import dev.networkstorage.domain.RemotePath
import dev.networkstorage.domain.SmbFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.TimeoutException
import javax.inject.Inject

class SmbjCopyClient @Inject constructor() : SmbCopyClient {
    override suspend fun exists(
        connection: ConnectionConfig,
        credential: Credential,
        relativePath: String,
    ): Boolean = withShare(connection, credential) { share ->
        val normalized = CopyDestinationPath.normalize(relativePath)
        val path = sharePath(connection, normalized)
        share.fileExists(path) || share.folderExists(path)
    }

    override suspend fun createDirectories(
        connection: ConnectionConfig,
        credential: Credential,
        relativeDirectory: String,
    ) = withShare(connection, credential) { share ->
        val normalized = CopyDestinationPath.normalize(relativeDirectory)
        createDirectoriesInternal(share, connection, normalized)
    }

    override suspend fun createPart(
        connection: ConnectionConfig,
        credential: Credential,
        destinationDirectory: String,
        originalFileName: String,
        operationId: String,
    ): RemotePartWriteHandle = withContext(Dispatchers.IO) {
        val destination = CopyDestinationPath.normalize(destinationDirectory)
        validateLeafName(originalFileName)
        validateOperationId(operationId)

        val resources = connect(connection, credential)
        try {
            createDirectoriesInternal(resources.share, connection, destination)
            repeat(MAX_PART_ATTEMPTS) {
                val candidateName = "$originalFileName.$operationId.${UUID.randomUUID()}.part"
                val relativePath = RemotePath.join(destination, candidateName)
                val remotePath = sharePath(connection, relativePath)
                try {
                    val file = resources.share.openFile(
                        remotePath,
                        EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE, AccessMask.DELETE),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_CREATE,
                        EnumSet.noneOf(SMB2CreateOptions::class.java),
                    )
                    val part = AppOwnedPart(relativePath, operationId)
                    return@withContext object : RemotePartWriteHandle {
                        override val part: AppOwnedPart = part
                        override val output = file.outputStream
                        override fun close() {
                            runCatching { output.close() }
                            runCatching { file.close() }
                            resources.close()
                        }
                    }
                } catch (error: Throwable) {
                    if (!isNameCollision(error)) throw error
                }
            }
            throw IllegalStateException("Unable to allocate unique application-owned .part file")
        } catch (error: Throwable) {
            resources.close()
            throw mapFailure(error)
        }
    }

    override suspend fun openPartRead(
        connection: ConnectionConfig,
        credential: Credential,
        part: AppOwnedPart,
    ): RemoteReadHandle = withContext(Dispatchers.IO) {
        validatePart(part)
        val resources = connect(connection, credential)
        try {
            val file = resources.share.openFile(
                sharePath(connection, part.relativePath),
                EnumSet.of(AccessMask.GENERIC_READ),
                EnumSet.noneOf(FileAttributes::class.java),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions::class.java),
            )
            object : RemoteReadHandle {
                override val input = file.inputStream
                override fun close() {
                    runCatching { input.close() }
                    runCatching { file.close() }
                    resources.close()
                }
            }
        } catch (error: Throwable) {
            resources.close()
            throw mapFailure(error)
        }
    }

    override suspend fun promotePart(
        connection: ConnectionConfig,
        credential: Credential,
        part: AppOwnedPart,
        finalRelativePath: String,
    ): PromotedUpload = withShare(connection, credential) { share ->
        validatePart(part)
        val finalPath = CopyDestinationPath.normalize(finalRelativePath)
        require(finalPath.isNotBlank()) { "Final destination must identify a file" }
        val remoteFinal = sharePath(connection, finalPath)
        require(!share.fileExists(remoteFinal) && !share.folderExists(remoteFinal)) {
            "Final destination already exists"
        }
        val file = share.openFile(
            sharePath(connection, part.relativePath),
            EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
            EnumSet.noneOf(FileAttributes::class.java),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.noneOf(SMB2CreateOptions::class.java),
        )
        file.use { it.rename(remoteFinal, false) }
        PromotedUpload(finalPath, part.operationId)
    }

    override suspend fun moveToBackup(
        connection: ConnectionConfig,
        credential: Credential,
        existingRelativePath: String,
        backupRelativePath: String,
    ) = withShare(connection, credential) { share ->
        val existing = CopyDestinationPath.normalize(existingRelativePath)
        val backup = normalizeBackupPath(backupRelativePath)
        require(existing.isNotBlank()) { "Existing destination must identify a file" }
        require(backup.isNotBlank()) { "Backup destination must identify a file" }
        val backupParent = RemotePath.parent(backup)
        createDirectoriesAllowBackup(share, connection, backupParent)
        val remoteBackup = sharePath(connection, backup)
        require(!share.fileExists(remoteBackup) && !share.folderExists(remoteBackup)) {
            "Backup destination already exists"
        }
        val file = share.openFile(
            sharePath(connection, existing),
            EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
            EnumSet.noneOf(FileAttributes::class.java),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.noneOf(SMB2CreateOptions::class.java),
        )
        file.use { it.rename(remoteBackup, false) }
    }

    override suspend fun removePart(
        connection: ConnectionConfig,
        credential: Credential,
        part: AppOwnedPart,
    ) = withShare(connection, credential) { share ->
        validatePart(part)
        val path = sharePath(connection, part.relativePath)
        if (share.fileExists(path)) share.rm(path)
    }

    override suspend fun removePromotedForRestore(
        connection: ConnectionConfig,
        credential: Credential,
        promoted: PromotedUpload,
    ) = withShare(connection, credential) { share ->
        validateOperationId(promoted.operationId)
        val normalized = CopyDestinationPath.normalize(promoted.relativePath)
        require(normalized == promoted.relativePath) { "Promoted upload path must be normalized" }
        require(normalized.isNotBlank()) { "Promoted upload must identify a file" }
        val path = sharePath(connection, normalized)
        if (share.fileExists(path)) share.rm(path)
    }

    private suspend fun <T> withShare(
        connection: ConnectionConfig,
        credential: Credential,
        block: (DiskShare) -> T,
    ): T = withContext(Dispatchers.IO) {
        val resources = connect(connection, credential)
        try {
            block(resources.share)
        } catch (error: Throwable) {
            throw mapFailure(error)
        } finally {
            resources.close()
        }
    }

    private fun connect(connection: ConnectionConfig, credential: Credential): SmbResources {
        try {
            val client = SMBClient()
            try {
                val transport = client.connect(connection.host, connection.port)
                try {
                    val session = transport.authenticate(
                        AuthenticationContext(connection.username, credential.password, connection.domain),
                    )
                    try {
                        val share = session.connectShare(connection.share) as DiskShare
                        return SmbResources(client, transport, session, share)
                    } catch (error: Throwable) {
                        session.close()
                        throw error
                    }
                } catch (error: Throwable) {
                    transport.close()
                    throw error
                }
            } catch (error: Throwable) {
                client.close()
                throw error
            }
        } catch (error: Throwable) {
            throw mapFailure(error)
        } finally {
            credential.password.fill('\u0000')
        }
    }

    private fun createDirectoriesInternal(
        share: DiskShare,
        connection: ConnectionConfig,
        relativeDirectory: String,
    ) {
        if (relativeDirectory.isBlank()) return
        var current = ""
        for (segment in relativeDirectory.split('/')) {
            current = RemotePath.join(current, segment)
            val remote = sharePath(connection, current)
            if (!share.folderExists(remote)) share.mkdir(remote)
        }
    }

    private fun createDirectoriesAllowBackup(
        share: DiskShare,
        connection: ConnectionConfig,
        relativeDirectory: String,
    ) {
        if (relativeDirectory.isBlank()) return
        val normalized = RemotePath.normalize(relativeDirectory)
        require(
            normalized == CopyDestinationPath.RESERVED_BACKUP_DIRECTORY ||
                normalized.startsWith("${CopyDestinationPath.RESERVED_BACKUP_DIRECTORY}/"),
        ) { "Backup directory must remain inside the reserved backup tree" }
        var current = ""
        for (segment in normalized.split('/')) {
            current = RemotePath.join(current, segment)
            val remote = sharePath(connection, current)
            if (!share.folderExists(remote)) share.mkdir(remote)
        }
    }

    private fun sharePath(connection: ConnectionConfig, relativePath: String): String {
        val base = CopyDestinationPath.normalize(connection.basePath)
        val relative = RemotePath.normalize(relativePath)
        return listOf(base, relative).filter { it.isNotBlank() }.joinToString("\\")
    }

    private fun normalizeBackupPath(path: String): String {
        val normalized = RemotePath.normalize(path)
        require(
            normalized.startsWith("${CopyDestinationPath.RESERVED_BACKUP_DIRECTORY}/"),
        ) { "Backup path must be inside the reserved backup tree" }
        return normalized
    }

    private fun validatePart(part: AppOwnedPart) {
        validateOperationId(part.operationId)
        val normalized = RemotePath.normalize(part.relativePath)
        require(normalized == part.relativePath) { "Part path must be normalized" }
        val leaf = normalized.substringAfterLast('/')
        require(leaf.endsWith(".part")) { "Application-owned upload must use .part suffix" }
        require(leaf.contains(".${part.operationId}.")) {
            "Application-owned upload does not match its operation ID"
        }
        require(
            !normalized.equals(CopyDestinationPath.RESERVED_BACKUP_DIRECTORY, ignoreCase = true) &&
                !normalized.startsWith("${CopyDestinationPath.RESERVED_BACKUP_DIRECTORY}/", ignoreCase = true),
        ) { "Part upload cannot live in reserved backup tree" }
    }

    private fun validateLeafName(name: String) {
        require(name.isNotBlank()) { "File name must not be blank" }
        require('\u0000' !in name) { "NUL is not allowed" }
        require('/' !in name && '\\' !in name) { "File name must be a single path segment" }
        require(name != "." && name != "..") { "Invalid file name" }
        require(!name.equals(CopyDestinationPath.RESERVED_BACKUP_DIRECTORY, ignoreCase = true)) {
            "Reserved backup name is not allowed"
        }
    }

    private fun validateOperationId(operationId: String) {
        require(operationId.matches(OPERATION_ID)) { "Invalid operation ID" }
    }

    private fun mapFailure(error: Throwable): Throwable {
        if (error is CancellationException) return error
        if (error is SmbFailure || error is IllegalArgumentException || error is IllegalStateException) return error
        return SmbFailure(mapError(error), error)
    }

    private fun mapError(error: Throwable): NetworkError = when (error) {
        is UnknownHostException -> NetworkError.HOST_NOT_FOUND
        is TimeoutException, is java.net.SocketTimeoutException -> NetworkError.TIMEOUT
        is ConnectException, is java.io.IOException -> NetworkError.CONNECTION
        else -> when {
            error.javaClass.simpleName.contains("Authentication", true) -> NetworkError.AUTHENTICATION
            error.message?.contains("STATUS_BAD_NETWORK_NAME") == true -> NetworkError.SHARE_NOT_FOUND
            error.message?.contains("STATUS_OBJECT_NAME_NOT_FOUND") == true -> NetworkError.REMOTE_NOT_FOUND
            else -> NetworkError.UNKNOWN
        }
    }

    private fun isNameCollision(error: Throwable): Boolean =
        error.message?.contains("STATUS_OBJECT_NAME_COLLISION") == true

    private class SmbResources(
        private val client: SMBClient,
        private val transport: Connection,
        private val session: Session,
        val share: DiskShare,
    ) : Closeable {
        override fun close() {
            runCatching { share.close() }
            runCatching { session.close() }
            runCatching { transport.close() }
            runCatching { client.close() }
        }
    }

    private companion object {
        const val MAX_PART_ATTEMPTS = 8
        val OPERATION_ID = Regex("[A-Za-z0-9._-]{1,128}")
    }
}
