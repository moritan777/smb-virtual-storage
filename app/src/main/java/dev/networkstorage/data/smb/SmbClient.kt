package dev.networkstorage.data.smb

import dev.networkstorage.domain.ConnectionConfig
import dev.networkstorage.domain.Credential
import dev.networkstorage.domain.RemoteEntry
import java.io.Closeable
import java.io.InputStream

interface RemoteReadHandle : Closeable { val input: InputStream }

interface SmbClient {
    suspend fun list(connection: ConnectionConfig, credential: Credential, relativeDirectory: String): List<RemoteEntry>
    suspend fun openRead(connection: ConnectionConfig, credential: Credential, relativePath: String): RemoteReadHandle
}
