package dev.networkstorage.data.smb

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.protocol.commons.EnumWithValue.EnumUtils
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import dev.networkstorage.domain.ConnectionConfig
import dev.networkstorage.domain.Credential
import dev.networkstorage.domain.NetworkError
import dev.networkstorage.domain.RemoteEntry
import dev.networkstorage.domain.RemotePath
import dev.networkstorage.domain.SmbFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

class SmbjClient @Inject constructor() : SmbClient {
    override suspend fun list(connection: ConnectionConfig, credential: Credential, relativeDirectory: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val relative = RemotePath.normalize(relativeDirectory)
        val root = RemotePath.normalize(connection.basePath)
        val remoteDirectory = listOf(root, relative).filter { it.isNotBlank() }.joinToString("\\")
        try {
            SMBClient().use { client ->
                client.connect(connection.host, connection.port).use { sessionConnection ->
                    val auth = AuthenticationContext(connection.username, credential.password, connection.domain)
                    sessionConnection.authenticate(auth).use { session ->
                        (session.connectShare(connection.share) as DiskShare).use { share ->
                            share.list(remoteDirectory).mapNotNull { info ->
                                coroutineContext.ensureActive()
                                val name = info.fileName
                                if (name == "." || name == "..") null else RemoteEntry(
                                    relativePath = RemotePath.join(relative, name),
                                    isDirectory = EnumUtils.isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                                    size = info.endOfFile,
                                    lastModified = info.lastWriteTime.toEpochMillis(),
                                )
                            }
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw SmbFailure(mapError(error), error)
        }
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
}
