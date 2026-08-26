package dev.networkstorage.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkFolderPickerPolicyTest {
    @Test fun shareRootAndNestedSelectionsKeepInternalFieldsSeparate() {
        assertEquals(NetworkFolderSelection("books", ""), NetworkFolderPickerPolicy.selection("books", ""))
        assertEquals(NetworkFolderSelection("books", "漫画/新刊"), NetworkFolderPickerPolicy.selection("books", "漫画/新刊"))
    }
    @Test fun folderListingFiltersFilesAndPreservesUnicode() {
        val entries = listOf(RemoteEntry("作品", true, 0, 1), RemoteEntry("book.cbz", false, 10, 1))
        assertEquals(listOf("作品"), NetworkFolderPickerPolicy.folders(entries))
    }
    @Test fun parentAndTraversalAreSafe() {
        assertEquals("a", NetworkFolderPickerPolicy.parent("a/b"))
        assertTrue(runCatching { NetworkFolderPickerPolicy.selection("books", "../escape") }.isFailure)
    }
}
