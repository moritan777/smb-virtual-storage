package dev.networkstorage.data.copy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CopyToSmbSchedulerTagTest {
    @Test
    fun `manual enqueue timestamp is recovered from tags`() {
        val tags = setOf(
            "unrelated",
            CopyToSmbScheduler.manualRunTag(1234L),
            CopyToSmbScheduler.manualRunTag(5678L),
        )

        assertEquals(5678L, CopyToSmbScheduler.manualEnqueuedAt(tags))
    }

    @Test
    fun `manual enqueue timestamp ignores missing or malformed tags`() {
        assertNull(CopyToSmbScheduler.manualEnqueuedAt(setOf("unrelated", "copy-to-smb-manual-enqueued-at-nope")))
    }
}
