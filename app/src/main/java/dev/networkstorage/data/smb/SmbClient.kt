package dev.networkstorage.data.smb

import dev.networkstorage.domain.ConnectionConfig
import dev.networkstorage.domain.Credential
import dev.networkstorage.domain.RemoteEntry

interface SmbClient {
    suspend fun list(connection: ConnectionConfig, credential: Credential, relativeDirectory: String): List<RemoteEntry>
}
