package dev.networkstorage.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WindowsConflictNamingTest {
    @Test
    fun `numbers normal extension`() {
        assertEquals("photo (1).jpg", WindowsConflictNaming.numbered("photo.jpg", 1))
    }

    @Test
    fun `numbers compound extension before final extension`() {
        assertEquals("archive.tar (1).gz", WindowsConflictNaming.numbered("archive.tar.gz", 1))
    }

    @Test
    fun `numbers extensionless file`() {
        assertEquals("README (1)", WindowsConflictNaming.numbered("README", 1))
    }

    @Test
    fun `numbers dot file as extensionless`() {
        assertEquals(".nomedia (1)", WindowsConflictNaming.numbered(".nomedia", 1))
    }

    @Test
    fun `finds first available numbered name`() {
        val occupied = setOf("photo (1).jpg", "photo (2).jpg")
        assertEquals(
            "photo (3).jpg",
            WindowsConflictNaming.firstAvailable("photo.jpg", occupied::contains),
        )
    }

    @Test
    fun `rejects invalid input`() {
        listOf("", "   ", ".", "..", "dir/photo.jpg", "dir\\photo.jpg", "bad\u0000name").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                WindowsConflictNaming.numbered(name, 1)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            WindowsConflictNaming.numbered("photo.jpg", 0)
        }
    }
}
