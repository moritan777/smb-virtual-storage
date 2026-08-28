package dev.networkstorage.data.copy

import dev.networkstorage.data.credential.CredentialStore
import dev.networkstorage.data.smb.AppOwnedPart
import dev.networkstorage.data.smb.PromotedUpload
import dev.networkstorage.data.smb.RemotePartWriteHandle
import dev.networkstorage.data.smb.RemoteReadHandle
import dev.networkstorage.data.smb.SmbClient
import dev.networkstorage.data.smb.SmbCopyClient
import dev.networkstorage.domain.ConnectionConfig
import dev.networkstorage.domain.Credential
import dev.networkstorage.domain.FolderMode
import dev.networkstorage.domain.RemoteEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

class CopyToSmbOrchestratorTest {
    @Test
    fun `keep both reuses numbered destination with identical hash`() = runTest {
        val files = linkedMapOf(
            "dest/photo.jpg" to bytes("old"),
            "dest/photo (1).jpg" to bytes("new-content"),
        )
        val copyClient = FakeCopyClient(files)
        val orchestrator = orchestrator(files, copyClient)

        val result = orchestrator.copyFile(
            connection = connection(),
            destinationDirectory = "dest",
            source = source("photo.jpg", "new-content"),
            conflictPolicy = CopyConflictPolicy.KEEP_BOTH,
            operationId = "op-1",
        )

        assertEquals(CopyFileStatus.REUSED_EXISTING, result.status)
        assertEquals("dest/photo (1).jpg", result.destinationRelativePath)
        assertEquals(2, files.size)
        assertFalse(files.keys.any { it.endsWith(".part") })
    }

    @Test
    fun `replace with backup retains old bytes and promotes verified upload`() = runTest {
        val files = linkedMapOf("dest/photo.jpg" to bytes("old-content"))
        val copyClient = FakeCopyClient(files)
        val orchestrator = orchestrator(files, copyClient)
        val now = Instant.parse("2026-08-28T12:34:56.789Z")

        val result = orchestrator.copyFile(
            connection = connection(),
            destinationDirectory = "dest",
            source = source("photo.jpg", "new-content"),
            conflictPolicy = CopyConflictPolicy.REPLACE_WITH_BACKUP,
            operationId = "op-2",
            now = now,
        )

        assertEquals(CopyFileStatus.COPIED, result.status)
        assertArrayEquals(bytes("new-content"), files["dest/photo.jpg"])
        val backup = requireNotNull(result.backupRelativePath)
        assertTrue(backup.startsWith(".network-storage-backup/20260828T123456789Z_op-2/"))
        assertArrayEquals(bytes("old-content"), files[backup])
        assertFalse(files.keys.any { it.endsWith(".part") })
    }

    @Test
    fun `source size mismatch never promotes and cleans its part`() = runTest {
        val files = linkedMapOf<String, ByteArray>()
        val copyClient = FakeCopyClient(files)
        val orchestrator = orchestrator(files, copyClient)
        val oversized = CopySourceFile(
            relativePath = "photo.jpg",
            size = 3L,
            openInput = { ByteArrayInputStream(bytes("four")) },
        )

        val error = runCatching {
            orchestrator.copyFile(
                connection = connection(),
                destinationDirectory = "dest",
                source = oversized,
                conflictPolicy = CopyConflictPolicy.KEEP_BOTH,
                operationId = "op-3",
            )
        }.exceptionOrNull()

        assertTrue(error is CopyIntegrityException)
        assertFalse(files.containsKey("dest/photo.jpg"))
        assertFalse(files.keys.any { it.endsWith(".part") })
    }

    private fun orchestrator(
        files: MutableMap<String, ByteArray>,
        copyClient: FakeCopyClient,
    ) = CopyToSmbOrchestrator(
        copyClient = copyClient,
        readClient = FakeReadClient(files),
        credentialStore = FakeCredentialStore(),
    )

    private fun connection() = ConnectionConfig(
        id = "connection-1",
        name = "NAS",
        host = "nas",
        port = 445,
        share = "share",
        basePath = "",
        username = "user",
        domain = null,
        mode = FolderMode.ON_DEMAND,
    )

    private fun source(relativePath: String, value: String): CopySourceFile {
        val data = bytes(value)
        return CopySourceFile(relativePath, data.size.toLong()) { ByteArrayInputStream(data) }
    }

    private class FakeCredentialStore : CredentialStore {
        override fun put(connectionId: String, password: CharArray) = Unit
        override fun get(connectionId: String) = Credential("secret".toCharArray())
        override fun remove(connectionId: String) = Unit
    }

    private class FakeReadClient(
        private val files: MutableMap<String, ByteArray>,
    ) : SmbClient {
        override suspend fun list(
            connection: ConnectionConfig,
            credential: Credential,
            relativeDirectory: String,
        ): List<RemoteEntry> = emptyList()

        override suspend fun openRead(
            connection: ConnectionConfig,
            credential: Credential,
            relativePath: String,
        ): RemoteReadHandle {
            val data = requireNotNull(files[relativePath])
            return readHandle(data)
        }
    }

    private class FakeCopyClient(
        private val files: MutableMap<String, ByteArray>,
    ) : SmbCopyClient {
        override suspend fun exists(
            connection: ConnectionConfig,
            credential: Credential,
            relativePath: String,
        ): Boolean = relativePath in files

        override suspend fun createDirectories(
            connection: ConnectionConfig,
            credential: Credential,
            relativeDirectory: String,
        ) = Unit

        override suspend fun createPart(
            connection: ConnectionConfig,
            credential: Credential,
            destinationDirectory: String,
            originalFileName: String,
            operationId: String,
        ): RemotePartWriteHandle {
            val path = listOf(destinationDirectory, "$originalFileName.$operationId.fake.part")
                .filter { it.isNotBlank() }
                .joinToString("/")
            val part = AppOwnedPart(path, operationId)
            val output = ByteArrayOutputStream()
            return object : RemotePartWriteHandle {
                private var closed = false
                override val part: AppOwnedPart = part
                override val output: OutputStream = output
                override fun close() {
                    if (closed) return
                    closed = true
                    files[path] = output.toByteArray()
                    output.close()
                }
            }
        }

        override suspend fun openPartRead(
            connection: ConnectionConfig,
            credential: Credential,
            part: AppOwnedPart,
        ): RemoteReadHandle = readHandle(requireNotNull(files[part.relativePath]))

        override suspend fun promotePart(
            connection: ConnectionConfig,
            credential: Credential,
            part: AppOwnedPart,
            finalRelativePath: String,
        ): PromotedUpload {
            require(finalRelativePath !in files) { "Final destination already exists" }
            files[finalRelativePath] = requireNotNull(files.remove(part.relativePath))
            return PromotedUpload(finalRelativePath, part.operationId)
        }

        override suspend fun moveToBackup(
            connection: ConnectionConfig,
            credential: Credential,
            existingRelativePath: String,
            backupRelativePath: String,
        ) {
            require(backupRelativePath !in files)
            files[backupRelativePath] = requireNotNull(files.remove(existingRelativePath))
        }

        override suspend fun restoreBackup(
            connection: ConnectionConfig,
            credential: Credential,
            backupRelativePath: String,
            originalRelativePath: String,
        ) {
            require(originalRelativePath !in files)
            files[originalRelativePath] = requireNotNull(files.remove(backupRelativePath))
        }

        override suspend fun removePart(
            connection: ConnectionConfig,
            credential: Credential,
            part: AppOwnedPart,
        ) {
            files.remove(part.relativePath)
        }

        override suspend fun removePromotedForRestore(
            connection: ConnectionConfig,
            credential: Credential,
            promoted: PromotedUpload,
        ) {
            files.remove(promoted.relativePath)
        }
    }

    private companion object {
        fun bytes(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

        fun readHandle(data: ByteArray): RemoteReadHandle = object : RemoteReadHandle {
            override val input: InputStream = ByteArrayInputStream(data)
            override fun close() = input.close()
        }
    }
}
