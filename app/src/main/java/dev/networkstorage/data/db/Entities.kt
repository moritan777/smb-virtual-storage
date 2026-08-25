package dev.networkstorage.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.networkstorage.domain.FolderMode
import dev.networkstorage.domain.ScanStatus

@Entity(tableName = "connections")
data class ConnectionEntity(@PrimaryKey val id: String, val name: String, val host: String, val port: Int, val share: String, val basePath: String, val username: String, val domain: String?, val rootMode: FolderMode, val createdAt: Long)

@Entity(tableName = "folder_rules", primaryKeys = ["connectionId", "relativePath"], foreignKeys = [ForeignKey(entity = ConnectionEntity::class, parentColumns = ["id"], childColumns = ["connectionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("connectionId")])
data class FolderRuleEntity(val connectionId: String, val relativePath: String, val mode: FolderMode)

@Entity(tableName = "indexed_entries", primaryKeys = ["connectionId", "relativePath"], foreignKeys = [ForeignKey(entity = ConnectionEntity::class, parentColumns = ["id"], childColumns = ["connectionId"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["connectionId", "parentPath"]), Index(value = ["connectionId", "lastSeenScanId"])])
data class IndexedEntryEntity(val connectionId: String, val relativePath: String, val parentPath: String, val name: String, val isDirectory: Boolean, val size: Long, val lastModified: Long, val mimeType: String?, val mode: FolderMode, val remoteExists: Boolean, val lastSeenAt: Long, val lastSeenScanId: String)

@Entity(tableName = "scan_runs", foreignKeys = [ForeignKey(entity = ConnectionEntity::class, parentColumns = ["id"], childColumns = ["connectionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("connectionId")])
data class ScanRunEntity(@PrimaryKey val id: String, val connectionId: String, val status: ScanStatus, val scannedEntries: Long, val startedAt: Long, val finishedAt: Long?, val error: String?)

data class ConnectionSummary(
    @androidx.room.Embedded val connection: ConnectionEntity,
    val entryCount: Long,
    val lastScanAt: Long?,
    val hasRootRule: Boolean,
)
