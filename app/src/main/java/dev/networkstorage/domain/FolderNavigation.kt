package dev.networkstorage.domain

object FolderNavigation {
    fun hasParent(path: String) = RemotePath.normalize(path).isNotEmpty()
    fun parent(path: String) = RemotePath.normalize(path).substringBeforeLast('/', "")
}
