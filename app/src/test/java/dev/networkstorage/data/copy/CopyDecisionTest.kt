package dev.networkstorage.data.copy

import kotlin.test.Test
import kotlin.test.assertEquals

class CopyDecisionTest {
    @Test
    fun unchangedDestinationIsSkippedBeforeConflictPolicy() {
        val result = CopyDecisionEngine.decide(
            CopyDecisionInput(true, "abc", "abc", CopyConflictPolicy.REPLACE_WITH_BACKUP),
            "photo.jpg",
        )
        assertEquals(CopyDecision.UNCHANGED, result.decision)
        assertEquals("photo.jpg", result.destinationRelativePath)
    }

    @Test
    fun replaceIsSelectedForDifferentExistingContent() {
        val result = CopyDecisionEngine.decide(
            CopyDecisionInput(true, "old", "new", CopyConflictPolicy.REPLACE_WITH_BACKUP),
            "photo.jpg",
        )
        assertEquals(CopyDecision.REPLACE, result.decision)
    }

    @Test
    fun keepBothUsesAvailableNumberedName() {
        val result = CopyDecisionEngine.decide(
            CopyDecisionInput(true, "old", "new", CopyConflictPolicy.KEEP_BOTH, availableNumberedDestination = "photo (2).jpg"),
            "photo.jpg",
        )
        assertEquals(CopyDecision.KEEP_BOTH, result.decision)
        assertEquals("photo (2).jpg", result.destinationRelativePath)
    }

    @Test
    fun keepBothReusesIdenticalNumberedFile() {
        val result = CopyDecisionEngine.decide(
            CopyDecisionInput(true, "old", "new", CopyConflictPolicy.KEEP_BOTH, reusableNumberedDestination = "photo (1).jpg"),
            "photo.jpg",
        )
        assertEquals(CopyDecision.REUSE_EXISTING, result.decision)
        assertEquals("photo (1).jpg", result.destinationRelativePath)
    }

    @Test
    fun newDestinationIsCopiedWithoutConflictHandling() {
        val result = CopyDecisionEngine.decide(
            CopyDecisionInput(false, null, "new", CopyConflictPolicy.KEEP_BOTH),
            "README",
        )
        assertEquals(CopyDecision.NEW, result.decision)
        assertEquals("README", result.destinationRelativePath)
    }
}
