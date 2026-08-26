package dev.networkstorage.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.paging.PagingSource
import dev.networkstorage.domain.ScanStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveConnection(value: ConnectionEntity)
    @Update suspend fun updateConnection(value: ConnectionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveRootRule(value: FolderRuleEntity)
    @Query("SELECT * FROM connections ORDER BY name") fun observeConnections(): Flow<List<ConnectionEntity>>
    @Query("SELECT * FROM connections WHERE id = :id") suspend fun connection(id: String): ConnectionEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertEntry(value: IndexedEntryEntity)
    @Query("SELECT indexed_entries.*, cache_entries.localDocumentUri AS cacheDocumentUri, cache_entries.size AS cacheSize, cache_entries.remoteSize AS cacheRemoteSize, cache_entries.remoteLastModified AS cacheRemoteLastModified, cache_entries.state AS cacheState FROM indexed_entries LEFT JOIN cache_entries ON cache_entries.connectionId=indexed_entries.connectionId AND cache_entries.relativePath=indexed_entries.relativePath WHERE indexed_entries.connectionId=:connectionId AND indexed_entries.parentPath=:parentPath ORDER BY indexed_entries.isDirectory DESC, indexed_entries.name COLLATE NOCASE ASC") fun children(connectionId: String, parentPath: String): PagingSource<Int, BrowserEntryRow>
    @Query("SELECT connections.*, (SELECT COUNT(*) FROM indexed_entries WHERE connectionId=connections.id) AS entryCount, (SELECT MAX(finishedAt) FROM scan_runs WHERE connectionId=connections.id AND status='SUCCEEDED') AS lastScanAt, EXISTS(SELECT 1 FROM folder_rules WHERE connectionId=connections.id AND relativePath='') AS hasRootRule FROM connections ORDER BY name COLLATE NOCASE") fun observeConnectionSummaries(): Flow<List<ConnectionSummary>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveScan(value: ScanRunEntity)
    @Query("UPDATE scan_runs SET status=:status, finishedAt=:finishedAt, error=:error WHERE id=:id") suspend fun finishScan(id: String, status: ScanStatus, finishedAt: Long, error: String?)
    @Query("UPDATE scan_runs SET scannedEntries=:count WHERE id=:id") suspend fun updateScanCount(id: String, count: Long)
    @Query("DELETE FROM indexed_entries WHERE connectionId=:connectionId AND lastSeenScanId != :successfulScanId") suspend fun deleteMissingEntries(connectionId: String, successfulScanId: String)
    @Query("DELETE FROM connections WHERE id=:connectionId") suspend fun deleteConnectionRow(connectionId: String)
    @Query("DELETE FROM folder_rules WHERE connectionId=:connectionId AND relativePath='' ") suspend fun deleteRootRuleRow(connectionId: String)
    @Query("DELETE FROM indexed_entries WHERE connectionId=:connectionId") suspend fun deleteIndexedEntries(connectionId: String)
    @Query("DELETE FROM scan_runs WHERE connectionId=:connectionId") suspend fun deleteScanRuns(connectionId: String)
    @Query("SELECT * FROM indexed_entries WHERE connectionId=:connectionId ORDER BY relativePath") suspend fun entriesForTest(connectionId: String): List<IndexedEntryEntity>
    @Query("SELECT COUNT(*) FROM folder_rules WHERE connectionId=:connectionId") suspend fun folderRuleCount(connectionId: String): Long
    @Query("SELECT COUNT(*) FROM scan_runs WHERE connectionId=:connectionId") suspend fun scanRunCount(connectionId: String): Long
    @Query("SELECT * FROM indexed_entries WHERE connectionId=:connectionId AND relativePath=:relativePath") suspend fun indexedEntry(connectionId: String, relativePath: String): IndexedEntryEntity?
    @Query("SELECT * FROM cache_entries WHERE connectionId=:connectionId AND relativePath=:relativePath") suspend fun cacheEntry(connectionId: String, relativePath: String): CacheEntryEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertCache(value: CacheEntryEntity)
    @Query("DELETE FROM cache_entries WHERE connectionId=:connectionId AND relativePath=:relativePath") suspend fun deleteCache(connectionId: String, relativePath: String)
    @Query("UPDATE cache_entries SET lastAccessed=:now, updatedAt=:now WHERE connectionId=:connectionId AND relativePath=:relativePath") suspend fun touchCache(connectionId: String, relativePath: String, now: Long)
    @Query("SELECT COALESCE(SUM(size), 0) FROM cache_entries WHERE state='CACHED'") fun observeCacheUsage(): Flow<Long>
    @Query("SELECT * FROM cache_entries WHERE state='CACHED' AND NOT (connectionId=:protectedConnectionId AND relativePath=:protectedPath) ORDER BY lastAccessed ASC") suspend fun lruCacheEntries(protectedConnectionId: String, protectedPath: String): List<CacheEntryEntity>
    @Query("SELECT * FROM cache_entries WHERE state='CACHED' ORDER BY lastAccessed ASC") suspend fun allCachedEntries(): List<CacheEntryEntity>
    @Query("SELECT cache_entries.* FROM cache_entries LEFT JOIN indexed_entries ON indexed_entries.connectionId=cache_entries.connectionId AND indexed_entries.relativePath=cache_entries.relativePath WHERE cache_entries.connectionId=:connectionId AND indexed_entries.relativePath IS NULL") suspend fun orphanCacheEntries(connectionId: String): List<CacheEntryEntity>
    @Query("DELETE FROM cache_entries") suspend fun deleteAllCacheRows()

    @Transaction suspend fun deleteConnection(connectionId: String) = deleteConnectionRow(connectionId)
    @Transaction suspend fun deleteRootIndex(connectionId: String) {
        deleteRootRuleRow(connectionId)
        deleteIndexedEntries(connectionId)
        deleteScanRuns(connectionId)
    }

    @Transaction
    suspend fun completeScan(connectionId: String, scanId: String, count: Long, now: Long) {
        deleteMissingEntries(connectionId, scanId)
        updateScanCount(scanId, count)
        finishScan(scanId, ScanStatus.SUCCEEDED, now, null)
    }
}
