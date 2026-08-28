package dev.networkstorage.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.networkstorage.data.copy.CopyConflictPolicy

enum class CopyNetworkPolicy {
    ANY_CONNECTED,
    UNMETERED_ONLY,
}

enum class CopyHistoryStatus {
    SUCCEEDED,
    SKIPPED_IDENTICAL,
    FAILED,
    CANCELLED,
}

enum class CopyErrorCode {
    AUTHENTICATION,
    HOST_NOT_FOUND,
    SHARE_NOT_FOUND,
    CONNECTION,
    TIMEOUT,
    REMOTE_NOT_FOUND,
    LOCAL_STORAGE,
    LOCAL_WRITE,
    REMOTE_WRITE,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    SOURCE_UNAVAILABLE,
    DESTINATION_CONFLICT,
    BACKUP_FAILED,
    PROMOTION_FAILED,
    RESTORE_FAILED,
    CANCELLED,
    UNKNOWN,
}

@Entity(
    tableName = "copy_rules",
    foreignKeys = [
        ForeignKey(
            entity = ConnectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["connectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("connectionId")],
)
data class CopyRuleEntity(
    @PrimaryKey val id: String,
    val connectionId: String,
    val sourceTreeUri: String,
    val destinationPath: String,
    val includeSubfolders: Boolean,
    val conflictPolicy: CopyConflictPolicy,
    val automaticCopyEnabled: Boolean,
    val networkPolicy: CopyNetworkPolicy,
    val requiresCharging: Boolean,
    val requiresBatteryNotLow: Boolean,
    val requiresStorageNotLow: Boolean,
    val periodicIntervalMinutes: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "copy_history",
    foreignKeys = [
        ForeignKey(
            entity = ConnectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["connectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CopyRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["ruleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("connectionId"),
        Index("ruleId"),
        Index("operationId"),
        Index(value = ["ruleId", "completedAt"]),
    ],
)
data class CopyHistoryEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val ruleId: String,
    val connectionId: String,
    val sourceRelativePath: String,
    val destinationRelativePath: String,
    val sourceSize: Long,
    val sha256: String?,
    val status: CopyHistoryStatus,
    val errorCode: CopyErrorCode?,
    val backupRelativePath: String?,
    val completedAt: Long,
)
