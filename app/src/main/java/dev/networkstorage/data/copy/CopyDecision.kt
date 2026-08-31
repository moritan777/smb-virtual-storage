package dev.networkstorage.data.copy

/**
 * The action that would be taken for one source file.
 *
 * This is deliberately side-effect free so the same vocabulary can be used by
 * dry-run previews, execution diagnostics, and tests.
 */
enum class CopyDecision {
    NEW,
    UNCHANGED,
    KEEP_BOTH,
    REPLACE,
    REUSE_EXISTING,
}

data class CopyDecisionInput(
    val originalExists: Boolean,
    val originalSha256: String?,
    val sourceSha256: String,
    val conflictPolicy: CopyConflictPolicy,
    val reusableNumberedDestination: String? = null,
    val availableNumberedDestination: String? = null,
)

data class CopyDecisionResult(
    val decision: CopyDecision,
    val destinationRelativePath: String,
    val reason: String,
)

object CopyDecisionEngine {
    fun decide(input: CopyDecisionInput, originalPath: String): CopyDecisionResult {
        require(originalPath.isNotBlank())
        if (input.originalExists && input.originalSha256 == input.sourceSha256) {
            return CopyDecisionResult(CopyDecision.UNCHANGED, originalPath, "Destination already has identical content")
        }

        return when (input.conflictPolicy) {
            CopyConflictPolicy.REPLACE_WITH_BACKUP -> CopyDecisionResult(
                if (input.originalExists) CopyDecision.REPLACE else CopyDecision.NEW,
                originalPath,
                if (input.originalExists) "Destination differs and will be replaced after backup" else "Destination does not exist",
            )
            CopyConflictPolicy.KEEP_BOTH -> when {
                input.reusableNumberedDestination != null -> CopyDecisionResult(
                    CopyDecision.REUSE_EXISTING,
                    input.reusableNumberedDestination,
                    "A numbered destination with identical content already exists",
                )
                input.originalExists && input.availableNumberedDestination != null -> CopyDecisionResult(
                    CopyDecision.KEEP_BOTH,
                    input.availableNumberedDestination,
                    "Destination differs; a numbered copy will be created",
                )
                !input.originalExists -> CopyDecisionResult(CopyDecision.NEW, originalPath, "Destination does not exist")
                else -> error("Keep Both requires either an available or reusable numbered destination")
            }
        }
    }
}

/** Aggregate counts used by dry-run and completed execution summaries. */
data class CopyOperationSummary(
    val total: Int,
    val newCount: Int,
    val unchangedCount: Int,
    val keepBothCount: Int,
    val replaceCount: Int,
    val reuseExistingCount: Int,
    val failedCount: Int = 0,
) {
    val plannedWrites: Int get() = newCount + keepBothCount + replaceCount
}
