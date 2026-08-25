package dev.networkstorage.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemotePathTest {
    @Test fun normalizesSeparatorsAndDots() { assertEquals("Manga/作品/01.cbz", RemotePath.normalize("Manga\\作品/./01.cbz")) }
    @Test fun joinsOnlyOneSafeChild() { assertEquals("A/B", RemotePath.join("A", "B")) }
    @Test fun rejectsTraversalAndAbsolutePaths() {
        assertThrows(IllegalArgumentException::class.java) { RemotePath.normalize("../secret") }
        assertThrows(IllegalArgumentException::class.java) { RemotePath.normalize("/absolute") }
        assertThrows(IllegalArgumentException::class.java) { RemotePath.join("safe", "a/b") }
    }
}
