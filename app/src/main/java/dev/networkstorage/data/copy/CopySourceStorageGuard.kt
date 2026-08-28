package dev.networkstorage.data.copy

import dev.networkstorage.data.settings.SettingsRepository
import dev.networkstorage.data.settings.TreeRelationship
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CopySourceStorageGuard @Inject constructor(
    private val settings: SettingsRepository,
) {
    suspend fun requireSafe(sourceTreeUri: String) {
        requireSafe(
            sourceTreeUri = sourceTreeUri,
            cacheRootUri = settings.cacheRootUri.first(),
            mirrorRootUri = settings.mirrorRootUri.first(),
        )
    }

    companion object {
        fun requireSafe(sourceTreeUri: String, cacheRootUri: String?, mirrorRootUri: String?) {
            require(!TreeRelationship.overlaps(sourceTreeUri, cacheRootUri)) {
                "Copy source must not overlap the cache folder"
            }
            require(!TreeRelationship.overlaps(sourceTreeUri, mirrorRootUri)) {
                "Copy source must not overlap the Mirror folder"
            }
        }
    }
}
