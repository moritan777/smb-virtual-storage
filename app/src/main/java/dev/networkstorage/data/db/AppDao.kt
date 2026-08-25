package dev.networkstorage.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.networkstorage.domain.ScanStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveConnection(value: ConnectionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveRootRule(value: FolderRuleEntity)
    @Query("SELECT * FROM connections ORDER BY name") fun observeConnections(): Flow<List<ConnectionEntity>>
    @Query("SELECT * FROM connections WHERE id = :id") suspend fun connection(id: String): ConnectionEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertEntry(value: IndexedEntryEntity)
    @Query("SELECT * FROM indexed_entries WHERE connectionId = :id ORDER BY relativePath LIMIT 200") fun observePreview(id: String): Flow<List<IndexedEntryEntity>>
    @Query("SELECT COUNT(*) FROM indexed_entries WHERE connectionId = :id AND remoteExists = 1") fun observeEntryCount(id: String): Flow<Long>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveScan(value: ScanRunEntity)
    @Query("UPDATE scan_runs SET status=:status, finishedAt=:finishedAt, error=:error WHERE id=:id") suspend fun finishScan(id: String, status: ScanStatus, finishedAt: Long, error: String?)
    @Query("UPDATE scan_runs SET scannedEntries=:count WHERE id=:id") suspend fun updateScanCount(id: String, count: Long)
    @Query("UPDATE indexed_entries SET remoteExists=0 WHERE connectionId=:connectionId AND lastSeenScanId != :successfulScanId") suspend fun markMissing(connectionId: String, successfulScanId: String)

    @Transaction
    suspend fun completeScan(connectionId: String, scanId: String, count: Long, now: Long) {
        markMissing(connectionId, scanId)
        updateScanCount(scanId, count)
        finishScan(scanId, ScanStatus.SUCCEEDED, now, null)
    }
}
