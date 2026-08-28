package dev.networkstorage

import org.junit.Assert.assertTrue
import org.junit.Test

class CopySchedulingUiTextTest {
    @Test
    fun automaticCopyExplainsWorkManagerTiming() {
        val message = "Automatic copy runs periodically, but Android WorkManager decides the exact execution time. The selected interval is not an exact clock schedule."

        assertTrue(message.contains("WorkManager"))
        assertTrue(message.contains("exact execution time"))
        assertTrue(message.contains("not an exact clock schedule"))
    }
}
