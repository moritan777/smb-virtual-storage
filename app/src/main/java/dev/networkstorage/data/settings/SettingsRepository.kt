package dev.networkstorage.data.settings

import android.content.Context
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore("settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    val cacheLimitBytes: Flow<Long> = context.settingsDataStore.data.map { it[CACHE_LIMIT] ?: DEFAULT_CACHE_LIMIT_BYTES }

    suspend fun setCacheLimitBytes(bytes: Long) {
        require(bytes >= BYTES_PER_GIB) { "Cache limit must be at least 1 GiB" }
        context.settingsDataStore.edit { it[CACHE_LIMIT] = bytes }
    }

    companion object {
        const val BYTES_PER_GIB = 1024L * 1024L * 1024L
        const val DEFAULT_CACHE_LIMIT_BYTES = 10L * BYTES_PER_GIB
        val PRESET_GIB = listOf(5L, 10L, 20L, 50L)
        private val CACHE_LIMIT = longPreferencesKey("cacheLimitBytes")

        fun gibToBytes(input: String): Result<Long> = runCatching {
            val gib = input.toLong()
            require(gib >= 1L)
            Math.multiplyExact(gib, BYTES_PER_GIB)
        }
    }
}
