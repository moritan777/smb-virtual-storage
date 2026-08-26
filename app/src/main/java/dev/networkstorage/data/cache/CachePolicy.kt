package dev.networkstorage.data.cache

import dev.networkstorage.domain.RemotePath

object CachePolicy {
    fun isValid(cacheRemoteSize: Long, cacheRemoteModified: Long, remoteSize: Long, remoteModified: Long) = cacheRemoteSize == remoteSize && cacheRemoteModified == remoteModified
    fun exceedsLimit(usageWithoutReplacedEntry: Long, incomingSize: Long, limit: Long) = usageWithoutReplacedEntry > Long.MAX_VALUE - incomingSize || usageWithoutReplacedEntry + incomingSize > limit
}

object CachePath {
    fun directoryParts(connectionId: String, relativePath: String): List<String> {
        val safeConnection = RemotePath.join("", connectionId)
        return listOf(safeConnection) + RemotePath.parent(RemotePath.normalize(relativePath)).split('/').filter(String::isNotBlank)
    }
}
