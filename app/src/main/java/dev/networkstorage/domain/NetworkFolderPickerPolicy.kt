package dev.networkstorage.domain

data class NetworkFolderSelection(val share: String, val basePath: String) {
    init { require(share.isNotBlank()); RemotePath.normalize(basePath) }
}

object NetworkFolderPickerPolicy {
    fun folders(entries: List<RemoteEntry>) = entries.filter(RemoteEntry::isDirectory).map(RemoteEntry::relativePath)
    fun selection(share: String, path: String) = NetworkFolderSelection(share, RemotePath.normalize(path))
    fun parent(path: String) = FolderNavigation.parent(path)
}
