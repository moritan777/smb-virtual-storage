package dev.networkstorage

import androidx.work.Data
import androidx.work.WorkInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CopyWorkSelectionTest {
    @Test
    fun `active manual work wins when periodic work is running`() {
        val manual = work(WorkInfo.State.RUNNING, "manual")
        val periodic = work(WorkInfo.State.RUNNING, "periodic")

        val selected = selectCopyWorkInfo(listOf(manual), listOf(periodic))

        assertSame(manual, selected?.info)
        assertFalse(selected?.automatic ?: true)
    }

    @Test
    fun `periodic work wins when no active manual work exists`() {
        val periodic = work(WorkInfo.State.RUNNING, "periodic")

        val selected = selectCopyWorkInfo(emptyList(), listOf(periodic))

        assertSame(periodic, selected?.info)
        assertTrue(selected?.automatic ?: false)
    }

    private fun work(state: WorkInfo.State, kind: String): WorkInfo =
        WorkInfo(
            UUID.nameUUIDFromBytes(kind.toByteArray()),
            state,
            emptySet(),
            Data.EMPTY,
            Data.EMPTY,
            0,
            0,
        )
}
