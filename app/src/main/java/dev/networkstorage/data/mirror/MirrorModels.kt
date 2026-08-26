package dev.networkstorage.data.mirror

enum class MirrorDiffState { REMOTE_ONLY, LOCAL_ONLY, SAME, REMOTE_NEWER, LOCAL_NEWER }

data class MirrorDiffItem(
    val relativePath: String,
    val name: String,
    val remoteSize: Long?,
    val remoteLastModified: Long?,
    val localSize: Long?,
    val localLastModified: Long?,
    val state: MirrorDiffState,
)

object MirrorDiffPolicy {
    private const val MTIME_TOLERANCE_MS = 2_000L

    fun classify(remoteSize: Long?, remoteModified: Long?, localSize: Long?, localModified: Long?): MirrorDiffState {
        if (remoteSize == null) return MirrorDiffState.LOCAL_ONLY
        if (localSize == null) return MirrorDiffState.REMOTE_ONLY
        val remoteTime = remoteModified ?: 0L
        val localTime = localModified ?: 0L
        if (remoteSize == localSize && (remoteTime == 0L || localTime == 0L || kotlin.math.abs(remoteTime - localTime) <= MTIME_TOLERANCE_MS)) return MirrorDiffState.SAME
        return if (remoteTime > localTime) MirrorDiffState.REMOTE_NEWER else MirrorDiffState.LOCAL_NEWER
    }
}
