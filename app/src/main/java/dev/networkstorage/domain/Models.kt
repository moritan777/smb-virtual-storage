package dev.networkstorage.domain

enum class FolderMode {
    @Deprecated("Legacy database value only; migrated to ON_DEMAND in database version 3")
    INDEX_ONLY,
    ON_DEMAND,
    MIRROR,
}

enum class ScanStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED }

enum class CacheState { DOWNLOADING, CACHED, FAILED }

enum class DownloadQueueState { QUEUED, DOWNLOADING, COMPLETED, FAILED, CANCELLED }

enum class NetworkError { AUTHENTICATION, HOST_NOT_FOUND, SHARE_NOT_FOUND, CONNECTION, TIMEOUT, REMOTE_NOT_FOUND, CANCELLED, UNKNOWN }

data class ConnectionConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val share: String,
    val basePath: String,
    val username: String,
    val domain: String?,
    val mode: FolderMode,
)

data class Credential(val password: CharArray)

data class RemoteEntry(val relativePath: String, val isDirectory: Boolean, val size: Long, val lastModified: Long)

class SmbFailure(val category: NetworkError, cause: Throwable? = null) : Exception(category.name, cause)
