package dev.networkstorage.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPolicyTest {
    @Test fun rootModeIsInheritedByIndexedChildren() {
        FolderMode.entries.forEach { mode -> assertEquals(mode, inheritedMode(mode, null)) }
    }
    @Test fun metadataChangeUsesLongSafeSizeAndMtime() {
        val old = Meta(Long.MAX_VALUE - 1, 100)
        assertTrue(changed(old, Meta(Long.MAX_VALUE, 100)))
        assertTrue(changed(old, Meta(Long.MAX_VALUE - 1, 101)))
        assertFalse(changed(old, old))
    }
    private fun inheritedMode(root: FolderMode, override: FolderMode?) = override ?: root
    private data class Meta(val size: Long, val modified: Long)
    private fun changed(a: Meta, b: Meta) = a.size != b.size || a.modified != b.modified
}
