package dev.networkstorage.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CopyDestinationPathTest {
    @Test
    fun `normalizes separators and dot segments`() {
        assertEquals("photos/2026", CopyDestinationPath.normalize("photos\\./2026//"))
    }

    @Test
    fun `empty destination represents connection root`() {
        assertEquals("", CopyDestinationPath.normalize(""))
    }

    @Test
    fun `joins source path beneath destination`() {
        assertEquals(
            "backup/camera/2026/photo.jpg",
            CopyDestinationPath.join("backup/camera", "2026/photo.jpg"),
        )
    }

    @Test
    fun `joins source path at connection root`() {
        assertEquals("photo.jpg", CopyDestinationPath.join("", "photo.jpg"))
    }

    @Test
    fun `rejects absolute traversal nul drive uri unc and reserved destinations`() {
        val rejected = listOf(
            "/photos",
            "\\\\server\\share",
            "../photos",
            "photos/../secret",
            "photos\u0000bad",
            "C:/photos",
            "c:\\photos",
            "smb://server/share",
            ".network-storage-backup",
            ".network-storage-backup/old/photo.jpg",
            "safe/.NETWORK-STORAGE-BACKUP/file",
        )

        rejected.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                CopyDestinationPath.normalize(value)
            }
        }
    }

    @Test
    fun `rejects source root escape and absolute source`() {
        listOf("../photo.jpg", "/photo.jpg", "\\photo.jpg", "C:/photo.jpg").forEach { source ->
            assertThrows(IllegalArgumentException::class.java) {
                CopyDestinationPath.join("uploads", source)
            }
        }
    }

    @Test
    fun `rejects blank source relative path`() {
        assertThrows(IllegalArgumentException::class.java) {
            CopyDestinationPath.join("uploads", "   ")
        }
    }
}
