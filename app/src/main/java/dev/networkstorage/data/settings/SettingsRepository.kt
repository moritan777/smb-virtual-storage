package dev.networkstorage.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore("settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    val cacheLimitBytes: Flow<Long> = context.settingsDataStore.data.map { it[CACHE_LIMIT] ?: DEFAULT_CACHE_LIMIT_BYTES }
    val cacheRootUri: Flow<String?> = context.settingsDataStore.data.map { it[CACHE_ROOT] }
    val mirrorRootUri: Flow<String?> = context.settingsDataStore.data.map { it[MIRROR_ROOT] }
    val automaticMirrorSyncEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[AUTOMATIC_MIRROR_SYNC_ENABLED_KEY] ?: false }
    val automaticMirrorSyncIntervalMinutes: Flow<Long> = context.settingsDataStore.data.map { it[AUTOMATIC_MIRROR_SYNC_INTERVAL_MINUTES_KEY] ?: DEFAULT_AUTOMATIC_MIRROR_SYNC_INTERVAL_MINUTES }

    suspend fun setCacheLimitBytes(bytes: Long) {
        require(bytes >= BYTES_PER_GIB) { "Cache limit must be at least 1 GiB" }
        context.settingsDataStore.edit { it[CACHE_LIMIT] = bytes }
    }

    suspend fun setStorageRoot(kind: StorageRootKind, uri: String) {
        val other = if (kind == StorageRootKind.CACHE) mirrorRootUri else cacheRootUri
        require(!TreeRelationship.overlaps(uri, other.first())) { "Cache and Mirror folders must be separate" }
        context.settingsDataStore.edit { it[if (kind == StorageRootKind.CACHE) CACHE_ROOT else MIRROR_ROOT] = uri }
    }

    suspend fun setAutomaticMirrorSyncEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[AUTOMATIC_MIRROR_SYNC_ENABLED_KEY] = enabled }
    }

    suspend fun setAutomaticMirrorSyncIntervalMinutes(minutes: Long) {
        require(minutes in AUTOMATIC_MIRROR_SYNC_INTERVAL_OPTIONS_MINUTES) { "Unsupported automatic mirror sync interval" }
        context.settingsDataStore.edit { it[AUTOMATIC_MIRROR_SYNC_INTERVAL_MINUTES_KEY] = minutes }
    }

    companion object {
        const val BYTES_PER_GIB = 1024L * 1024L * 1024L
        const val DEFAULT_CACHE_LIMIT_BYTES = 10L * BYTES_PER_GIB
        val PRESET_GIB = listOf(5L, 10L, 20L, 50L)
        val AUTOMATIC_MIRROR_SYNC_INTERVAL_OPTIONS_MINUTES = listOf(15L, 60L, 360L, 1440L)
        const val DEFAULT_AUTOMATIC_MIRROR_SYNC_INTERVAL_MINUTES = 60L
        private val CACHE_LIMIT = longPreferencesKey("cacheLimitBytes")
        private val CACHE_ROOT = stringPreferencesKey("cacheRootUri")
        private val MIRROR_ROOT = stringPreferencesKey("mirrorRootUri")
        private val AUTOMATIC_MIRROR_SYNC_ENABLED_KEY = booleanPreferencesKey("automaticMirrorSyncEnabled")
        private val AUTOMATIC_MIRROR_SYNC_INTERVAL_MINUTES_KEY = longPreferencesKey("automaticMirrorSyncIntervalMinutes")

        fun gibToBytes(input: String): Result<Long> = runCatching {
            val gib = input.toLong()
            require(gib >= 1L)
            Math.multiplyExact(gib, BYTES_PER_GIB)
        }
    }
}

enum class StorageRootKind { CACHE, MIRROR }

object TreeRelationship {
    fun overlaps(first: String, second: String?): Boolean {
        if (second == null) return false
        val a = android.net.Uri.parse(first)
        val b = android.net.Uri.parse(second)
        if (a.authority != b.authority) return false
        val aId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(a) }.getOrNull() ?: return true
        val bId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(b) }.getOrNull() ?: return true
        fun contains(parent: String, child: String) = child == parent || child.startsWith(parent.trimEnd('/') + "/")
        return contains(aId, bId) || contains(bId, aId)
    }
}
