package dev.networkstorage.data.copy

import dev.networkstorage.data.db.AppDao
import dev.networkstorage.data.db.CopyErrorCode
import dev.networkstorage.data.db.CopyHistoryEntity
import dev.networkstorage.data.db.CopyHistoryStatus
import dev.networkstorage.data.db.CopyNetworkPolicy
import dev.networkstorage.data.db.CopyRuleEntity
import dev.networkstorage.domain.CopyDestinationPath
import kotlinx.coroutines.flow.Flow
import java.net.URI
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CopyPersistenceRepository @Inject constructor(
    private val dao: AppDao,
) {
    fun observeRules(connectionId: String): Flow<List<CopyRuleEntity>> = dao.observeCopyRules(connectionId)
    fun observeRecentHistory(ruleId: String, limit: Int = DEFAULT_VISIBLE_HISTORY): Flow<List<CopyHistoryEntity>> {
        require(limit in 1..MAX_HISTORY_LIMIT)
        return dao.observeRecentCopyHistory(ruleId, limit)
    }
    suspend fun rule(ruleId: String): CopyRuleEntity? = dao.copyRule(ruleId)
    suspend fun allRules(): List<CopyRuleEntity> = dao.allCopyRules()
    suspend fun automaticRules(): List<CopyRuleEntity> = dao.automaticCopyRules()

    suspend fun saveRule(
        id: String,
        connectionId: String,
        sourceTreeUri: String,
        destinationPath: String,
        includeSubfolders: Boolean,
        conflictPolicy: CopyConflictPolicy,
        automaticCopyEnabled: Boolean,
        networkPolicy: CopyNetworkPolicy,
        requiresCharging: Boolean,
        requiresBatteryNotLow: Boolean,
        requiresStorageNotLow: Boolean,
        periodicIntervalMinutes: Long,
        createdAt: Long,
        updatedAt: Long,
    ): CopyRuleEntity {
        require(id.isNotBlank()) { "Rule ID must not be blank" }
        require(connectionId.isNotBlank()) { "Connection ID must not be blank" }
        require(updatedAt >= createdAt) { "updatedAt must not precede createdAt" }
        require(periodicIntervalMinutes in SUPPORTED_INTERVALS) { "Unsupported periodic interval" }
        requirePersistedTreeUri(sourceTreeUri)
        val value = CopyRuleEntity(
            id, connectionId, sourceTreeUri, CopyDestinationPath.normalize(destinationPath), includeSubfolders,
            conflictPolicy, automaticCopyEnabled, networkPolicy, requiresCharging, requiresBatteryNotLow,
            requiresStorageNotLow, periodicIntervalMinutes, createdAt, updatedAt,
        )
        dao.saveCopyRule(value)
        return value
    }

    suspend fun deleteRule(ruleId: String) = dao.deleteCopyRule(ruleId)

    suspend fun recordResult(operationId: String, ruleId: String, connectionId: String, sourceRelativePath: String, result: CopyFileResult, completedAt: Long): CopyHistoryEntity {
        val status = when (result.status) {
            CopyFileStatus.COPIED -> CopyHistoryStatus.SUCCEEDED
            CopyFileStatus.UNCHANGED, CopyFileStatus.REUSED_EXISTING -> CopyHistoryStatus.SKIPPED_IDENTICAL
        }
        return saveHistory(operationId, ruleId, connectionId, sourceRelativePath, result.destinationRelativePath, result.sourceSize, result.sha256, status, null, result.backupRelativePath, completedAt)
    }

    suspend fun recordFailure(operationId: String, ruleId: String, connectionId: String, sourceRelativePath: String, destinationRelativePath: String, sourceSize: Long, status: CopyHistoryStatus, errorCode: CopyErrorCode, completedAt: Long): CopyHistoryEntity {
        require(status == CopyHistoryStatus.FAILED || status == CopyHistoryStatus.CANCELLED)
        return saveHistory(operationId, ruleId, connectionId, sourceRelativePath, destinationRelativePath, sourceSize, null, status, errorCode, null, completedAt)
    }

    suspend fun recentHistory(ruleId: String, limit: Int = 100): List<CopyHistoryEntity> {
        require(limit in 1..MAX_HISTORY_LIMIT)
        return dao.recentCopyHistory(ruleId, limit)
    }

    suspend fun historyForOperation(operationId: String): List<CopyHistoryEntity> = dao.copyHistoryForOperation(operationId)

    private suspend fun saveHistory(operationId: String, ruleId: String, connectionId: String, sourceRelativePath: String, destinationRelativePath: String, sourceSize: Long, sha256: String?, status: CopyHistoryStatus, errorCode: CopyErrorCode?, backupRelativePath: String?, completedAt: Long): CopyHistoryEntity {
        require(operationId.isNotBlank())
        require(ruleId.isNotBlank())
        require(connectionId.isNotBlank())
        require(sourceRelativePath.isNotBlank())
        require(sourceSize >= 0L)
        require(sha256 == null || SHA256.matches(sha256))
        val value = CopyHistoryEntity(UUID.randomUUID().toString(), operationId, ruleId, connectionId, sourceRelativePath, destinationRelativePath, sourceSize, sha256, status, errorCode, backupRelativePath, completedAt)
        dao.saveCopyHistory(value, HISTORY_RETENTION_PER_RULE)
        return value
    }

    private fun requirePersistedTreeUri(value: String) {
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Invalid source tree URI", it) }
        require(uri.scheme == "content")
        require(!uri.authority.isNullOrBlank())
    }

    companion object {
        val SUPPORTED_INTERVALS = setOf(15L, 60L, 360L, 1440L)
        const val HISTORY_RETENTION_PER_RULE = 500
        const val DEFAULT_VISIBLE_HISTORY = 20
        private const val MAX_HISTORY_LIMIT = HISTORY_RETENTION_PER_RULE
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
