package dev.networkstorage.data.copy

import org.junit.Assert.assertEquals
import org.junit.Test

class TreeCopyProgressTest {
    @Test
    fun `progress counts copied skipped and failed outcomes`() {
        val outcomes = listOf(
            success(CopyFileStatus.COPIED),
            success(CopyFileStatus.UNCHANGED),
            TreeCopyFileOutcome.Failed("failed.txt", IllegalStateException("failed")),
        )

        val progress = TreeCopyProgress.from(outcomes, totalCount = 5)

        assertEquals(3, progress.completedCount)
        assertEquals(5, progress.totalCount)
        assertEquals(1, progress.copiedCount)
        assertEquals(1, progress.skippedCount)
        assertEquals(1, progress.failureCount)
    }

    private fun success(status: CopyFileStatus) = TreeCopyFileOutcome.Success(
        sourceRelativePath = "$status.txt",
        result = CopyFileResult(
            status = status,
            destinationRelativePath = "$status.txt",
            sourceSize = 1,
            sha256 = "00",
        ),
    )
}
