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
    fun observeRules(connectionId: String): Flow<List<CopyRuleEntity>> =
        dao.observeCopyRules(connectionId)

    suspend fun rule(ruleId: String): CopyRuleEntity? = dao.copyRule(ruleId)

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
            id = id,
            connectionId = connectionId,
            sourceTreeUri = sourceTreeUri,
            destinationPath = CopyDestinationPath.normalize(destinationPath),
            includeSubfolders = includeSubfolders,
            conflictPolicy = conflictPolicy,
            automaticCopyEnabled = automaticCopyEnabled,
            networkPolicy = networkPolicy,
            requiresCharging = requiresCharging,
            requiresBatteryNotLow = requiresBatteryNotLow,
            requiresStorageNotLow = requiresStorageNotLow,
            periodicIntervalMinutes = periodicIntervalMinutes,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
        dao.saveCopyRule(value)
        return value
    }

    suspend fun deleteRule(ruleId: String) = dao.deleteCopyRule(ruleId)

    suspend fun recordResult(
        operationId: String,
        ruleId: String,
        connectionId: String,
        sourceRelativePath: String,
        result: CopyFileResult,
        completedAt: Long,
    ): CopyHistoryEntity {
        val status = when (result.status) {
            CopyFileStatus.COPIED -> CopyHistoryStatus.SUCCEEDED
            CopyFileStatus.UNCHANGED,
            CopyFileStatus.REUSED_EXISTING,
            -> CopyHistoryStatus.SKIPPED_IDENTICAL
        }
        return saveHistory(
            operationId = operationId,
            ruleId = ruleId,
            connectionId = connectionId,
            sourceRelativePath = sourceRelativePath,
            destinationRelativePath = result.destinationRelativePath,
            sourceSize = result.sourceSize,
            sha256 = result.sha256,
            status = status,
            errorCode = null,
            backupRelativePath = result.backupRelativePath,
            completedAt = completedAt,
        )
    }

    suspend fun recordFailure(
        operationId: String,
        ruleId: String,
        connectionId: String,
        sourceRelativePath: String,
        destinationRelativePath: String,
        sourceSize: Long,
        status: CopyHistoryStatus,
        errorCode: CopyErrorCode,
        completedAt: Long,
    ): CopyHistoryEntity {
        require(status == CopyHistoryStatus.FAILED || status == CopyHistoryStatus.CANCELLED) {
            "Failure history requires FAILED or CANCELLED status"
        }
        return saveHistory(
            operationId = operationId,
            ruleId = ruleId,
            connectionId = connectionId,
            sourceRelativePath = sourceRelativePath,
            destinationRelativePath = destinationRelativePath,
            sourceSize = sourceSize,
            sha256 = null,
            status = status,
            errorCode = errorCode,
            backupRelativePath = null,
            completedAt = completedAt,
        )
    }

    suspend fun recentHistory(ruleId: String, limit: Int = 100): List<CopyHistoryEntity> {
        require(limit in 1..MAX_HISTORY_LIMIT) { "Invalid history limit" }
        return dao.recentCopyHistory(ruleId, limit)
    }

    suspend fun historyForOperation(operationId: String): List<CopyHistoryEntity> =
        dao.copyHistoryForOperation(operationId)

    private suspend fun saveHistory(
        operationId: String,
        ruleId: String,
        connectionId: String,
        sourceRelativePath: String,
        destinationRelativePath: String,
        sourceSize: Long,
        sha256: String?,
        status: CopyHistoryStatus,
        errorCode: CopyErrorCode?,
        backupRelativePath: String?,
        completedAt: Long,
    ): CopyHistoryEntity {
        require(operationId.isNotBlank()) { "Operation ID must not be blank" }
        require(ruleId.isNotBlank()) { "Rule ID must not be blank" }
        require(connectionId.isNotBlank()) { "Connection ID must not be blank" }
        require(sourceRelativePath.isNotBlank()) { "Source-relative path must not be blank" }
        require(sourceSize >= 0L) { "Source size must be non-negative" }
        require(sha256 == null || SHA256.matches(sha256)) { "Invalid SHA-256" }
        val value = CopyHistoryEntity(
            id = UUID.randomUUID().toString(),
            operationId = operationId,
            ruleId = ruleId,
            connectionId = connectionId,
            sourceRelativePath = sourceRelativePath,
            destinationRelativePath = destinationRelativePath,
            sourceSize = sourceSize,
            sha256 = sha256,
            status = status,
            errorCode = errorCode,
            backupRelativePath = backupRelativePath,
            completedAt = completedAt,
        )
        dao.saveCopyHistory(value)
        return value
    }

    private fun requirePersistedTreeUri(value: String) {
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Invalid source tree URI", it) }
        require(uri.scheme == "content") { "Copy source must be a SAF content URI" }
        require(!uri.authority.isNullOrBlank()) { "Copy source URI must have an authority" }
    }

    companion object {
        val SUPPORTED_INTERVALS = setOf(15L, 60L, 360L, 1440L)
        private const val MAX_HISTORY_LIMIT = 500
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
