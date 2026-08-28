package dev.networkstorage.data.copy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceRelativePathTest {
    @Test
    fun `joins nested source names without losing relative structure`() {
        assertEquals("photos/2026/image.jpg", SourceRelativePath.join("photos/2026", "image.jpg"))
    }

    @Test
    fun `joins a root file`() {
        assertEquals("image.jpg", SourceRelativePath.join("", "image.jpg"))
    }

    @Test
    fun `rejects names that could introduce another path component`() {
        listOf("", "   ", ".", "..", "a/b", "a\\b", "bad\u0000name").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                SourceRelativePath.join("", name)
            }
        }
    }

    @Test
    fun `rejects malformed parent paths`() {
        listOf("/photos", "photos/", "photos//2026", "photos/../2026").forEach { parent ->
            assertThrows(IllegalArgumentException::class.java) {
                SourceRelativePath.join(parent, "image.jpg")
            }
        }
    }
}
