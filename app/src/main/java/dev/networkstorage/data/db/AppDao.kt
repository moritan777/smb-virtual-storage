package dev.networkstorage.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.paging.PagingSource
import dev.networkstorage.domain.ScanStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveConnection(value: ConnectionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveRootRule(value: FolderRuleEntity)
    @Query("SELECT * FROM connections ORDER BY name") fun observeConnections(): Flow<List<ConnectionEntity>>
    @Query("SELECT * FROM connections WHERE id = :id") suspend fun connection(id: String): ConnectionEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertEntry(value: IndexedEntryEntity)
    @Query("SELECT * FROM indexed_entries WHERE connectionId=:connectionId AND parentPath=:parentPath ORDER BY isDirectory DESC, name COLLATE NOCASE ASC") fun children(connectionId: String, parentPath: String): PagingSource<Int, IndexedEntryEntity>
    @Query("SELECT connections.*, (SELECT COUNT(*) FROM indexed_entries WHERE connectionId=connections.id) AS entryCount, (SELECT MAX(finishedAt) FROM scan_runs WHERE connectionId=connections.id AND status='SUCCEEDED') AS lastScanAt, EXISTS(SELECT 1 FROM folder_rules WHERE connectionId=connections.id AND relativePath='') AS hasRootRule FROM connections ORDER BY name COLLATE NOCASE") fun observeConnectionSummaries(): Flow<List<ConnectionSummary>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveScan(value: ScanRunEntity)
    @Query("UPDATE scan_runs SET status=:status, finishedAt=:finishedAt, error=:error WHERE id=:id") suspend fun finishScan(id: String, status: ScanStatus, finishedAt: Long, error: String?)
    @Query("UPDATE scan_runs SET scannedEntries=:count WHERE id=:id") suspend fun updateScanCount(id: String, count: Long)
    @Query("UPDATE indexed_entries SET remoteExists=0 WHERE connectionId=:connectionId AND lastSeenScanId != :successfulScanId") suspend fun markMissing(connectionId: String, successfulScanId: String)
    @Query("DELETE FROM connections WHERE id=:connectionId") suspend fun deleteConnectionRow(connectionId: String)
    @Query("DELETE FROM folder_rules WHERE connectionId=:connectionId AND relativePath='' ") suspend fun deleteRootRuleRow(connectionId: String)
    @Query("DELETE FROM indexed_entries WHERE connectionId=:connectionId") suspend fun deleteIndexedEntries(connectionId: String)
    @Query("DELETE FROM scan_runs WHERE connectionId=:connectionId") suspend fun deleteScanRuns(connectionId: String)
    @Query("SELECT * FROM indexed_entries WHERE connectionId=:connectionId ORDER BY relativePath") suspend fun entriesForTest(connectionId: String): List<IndexedEntryEntity>
    @Query("SELECT COUNT(*) FROM folder_rules WHERE connectionId=:connectionId") suspend fun folderRuleCount(connectionId: String): Long
    @Query("SELECT COUNT(*) FROM scan_runs WHERE connectionId=:connectionId") suspend fun scanRunCount(connectionId: String): Long

    @Transaction suspend fun deleteConnection(connectionId: String) = deleteConnectionRow(connectionId)
    @Transaction suspend fun deleteRootIndex(connectionId: String) {
        deleteRootRuleRow(connectionId)
        deleteIndexedEntries(connectionId)
        deleteScanRuns(connectionId)
    }

    @Transaction
    suspend fun completeScan(connectionId: String, scanId: String, count: Long, now: Long) {
        markMissing(connectionId, scanId)
        updateScanCount(scanId, count)
        finishScan(scanId, ScanStatus.SUCCEEDED, now, null)
    }
}
