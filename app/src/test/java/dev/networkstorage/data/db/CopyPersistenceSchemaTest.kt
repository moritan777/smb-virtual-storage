package dev.networkstorage.data.db

import dev.networkstorage.data.copy.CopyPersistenceRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyPersistenceSchemaTest {
    @Test
    fun `periodic intervals are limited to supported WorkManager choices`() {
        assertEquals(setOf(15L, 60L, 360L, 1440L), CopyPersistenceRepository.SUPPORTED_INTERVALS)
    }

    @Test
    fun `copy history retention is explicitly bounded per rule`() {
        assertEquals(500, CopyPersistenceRepository.HISTORY_RETENTION_PER_RULE)
    }

    @Test
    fun `copy history stores no credential or raw error field`() {
        val names = CopyHistoryEntity::class.java.declaredFields.map { it.name }.toSet()

        assertFalse("password" in names)
        assertFalse("credential" in names)
        assertFalse("error" in names)
        assertFalse("errorMessage" in names)
        assertTrue("errorCode" in names)
        assertTrue("sha256" in names)
        assertTrue("backupRelativePath" in names)
    }

    @Test
    fun `safe error vocabulary includes replacement restoration failure`() {
        assertTrue(CopyErrorCode.RESTORE_FAILED in CopyErrorCode.entries)
        assertTrue(CopyErrorCode.HASH_MISMATCH in CopyErrorCode.entries)
        assertTrue(CopyErrorCode.SOURCE_UNAVAILABLE in CopyErrorCode.entries)
    }
}
