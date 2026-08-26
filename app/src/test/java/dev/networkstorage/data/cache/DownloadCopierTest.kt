package dev.networkstorage.data.cache

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DownloadCopierTest {
    @Test fun zeroByteCompletes() = runTest { assertEquals(0L, DownloadCopier.copy(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), 0)) }
    @Test fun boundedCopyPreservesSmallAndSimulatedFiftyMbFiles() = runTest {
        listOf(37, 50 * 1024 * 1024).forEach { size ->
            val source = ByteArray(size) { (it % 251).toByte() }
            val output = ByteArrayOutputStream()
            assertEquals(size.toLong(), DownloadCopier.copy(ByteArrayInputStream(source), output, size.toLong()))
            assertArrayEquals(source, output.toByteArray())
        }
    }
    @Test fun mismatchFailsWithoutPromotionDecision() = runTest {
        var failed = false
        try { DownloadCopier.copy(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), 2) }
        catch (_: SizeMismatchException) { failed = true }
        assertTrue(failed)
    }
}
