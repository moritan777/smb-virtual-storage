package dev.networkstorage.data.cache

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

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

    @Test fun interruptedSourceFailsInsteadOfReportingSuccessfulCopy() = runTest {
        val source = object : InputStream() {
            private var delivered = false
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!delivered) {
                    delivered = true
                    buffer[offset] = 1
                    buffer[offset + 1] = 2
                    return 2
                }
                throw IOException("simulated network disconnect")
            }
        }
        val output = ByteArrayOutputStream()
        var failed = false
        try {
            DownloadCopier.copy(source, output, 4)
        } catch (_: IOException) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(2, output.size())
    }
}
