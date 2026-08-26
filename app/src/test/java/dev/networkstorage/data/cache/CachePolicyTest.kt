package dev.networkstorage.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachePolicyTest {
    @Test fun validityRequiresBothRemoteSizeAndModifiedTime() {
        assertTrue(CachePolicy.isValid(10, 20, 10, 20))
        assertFalse(CachePolicy.isValid(9, 20, 10, 20))
        assertFalse(CachePolicy.isValid(10, 19, 10, 20))
    }

    @Test fun limitDetectionIsLongSafeAndDoesNotEvictAtExactBoundary() {
        assertFalse(CachePolicy.exceedsLimit(5, 5, 10))
        assertTrue(CachePolicy.exceedsLimit(6, 5, 10))
        assertFalse(CachePolicy.exceedsLimit(1L shl 30, 0, 1L shl 30))
        assertTrue(CachePolicy.exceedsLimit((1L shl 30) + 1, 0, 1L shl 30))
        assertTrue(CachePolicy.exceedsLimit(Long.MAX_VALUE, 1, Long.MAX_VALUE))
    }

    @Test fun cachePathPreservesUnicodeAndSeparatesConnections() {
        assertEquals(listOf("id-1", "作品", "巻一"), CachePath.directoryParts("id-1", "作品/巻一/book.cbz"))
        assertTrue(CachePath.directoryParts("id-2", "作品/book.cbz").first() != CachePath.directoryParts("id-1", "作品/book.cbz").first())
        assertTrue(runCatching { CachePath.directoryParts("../escape", "book.cbz") }.isFailure)
    }
}
