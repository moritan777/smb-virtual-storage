package dev.networkstorage.data.copy

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serializes all in-process Copy to SMB execution for the same durable rule.
 *
 * WorkManager unique work prevents duplicate manual/periodic requests of the same kind,
 * while this gate also prevents manual and periodic workers for one rule from copying
 * concurrently when their scheduling windows overlap.
 */
@Singleton
class CopyRuleExecutionGate @Inject constructor() {
    private val guard = Mutex()
    private val ruleLocks = mutableMapOf<String, Mutex>()

    suspend fun <T> withRuleLock(ruleId: String, block: suspend () -> T): T {
        require(ruleId.isNotBlank()) { "Rule ID must not be blank" }
        val mutex = guard.withLock { ruleLocks.getOrPut(ruleId) { Mutex() } }
        return mutex.withLock { block() }
    }
}
