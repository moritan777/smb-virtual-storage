package dev.networkstorage

import dev.networkstorage.domain.FolderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateTest {
    @Test fun editorMapsShareAndBasePathToOneUserFacingFolder() {
        val root = ConnectionEditorState(host="nas", share="books", basePath="")
        assertEquals("\\\\nas\\books", root.networkFolder)
        assertEquals("\\\\nas\\books\\漫画\\新刊", root.copy(basePath="漫画/新刊").networkFolder)
        assertEquals("books", root.share)
        assertEquals("", root.basePath)
    }
    @Test fun addAndExistingEditorStateRemainDistinctWithoutPasswordData() {
        assertEquals(null, ConnectionEditorState().id)
        val edit = ConnectionEditorState(id="id", username="user", share="books", mode=FolderMode.ON_DEMAND)
        assertEquals("user", edit.username)
        assertFalse(edit.toString().contains("password", ignoreCase=true))
    }
    @Test fun browserPresentationUsesIconsAndParentState() {
        assertEquals("☁", BrowserPresentation.stateIcon(LocalFileState.REMOTE_ONLY))
        assertEquals("✓", BrowserPresentation.stateIcon(LocalFileState.CACHED))
        assertEquals("↓", BrowserPresentation.stateIcon(LocalFileState.REMOTE_UPDATED))
        assertFalse(BrowserPresentation.hasParent(""))
        assertTrue(BrowserPresentation.hasParent("作品/新刊"))
    }
}
