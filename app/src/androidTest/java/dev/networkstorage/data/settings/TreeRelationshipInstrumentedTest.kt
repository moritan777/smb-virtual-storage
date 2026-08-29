package dev.networkstorage.data.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeRelationshipInstrumentedTest {
    @Test
    fun sameTreeOverlaps() {
        val tree = treeUri("com.android.externalstorage.documents", "primary:Documents/CopySource")

        assertTrue(TreeRelationship.overlaps(tree, tree))
    }

    @Test
    fun parentAndChildOverlapInBothDirections() {
        val parent = treeUri("com.android.externalstorage.documents", "primary:Documents")
        val child = treeUri("com.android.externalstorage.documents", "primary:Documents/CopySource")

        assertTrue(TreeRelationship.overlaps(parent, child))
        assertTrue(TreeRelationship.overlaps(child, parent))
    }

    @Test
    fun siblingTreesDoNotOverlap() {
        val first = treeUri("com.android.externalstorage.documents", "primary:Documents/CopySource")
        val second = treeUri("com.android.externalstorage.documents", "primary:Documents/Cache")

        assertFalse(TreeRelationship.overlaps(first, second))
    }

    @Test
    fun differentProvidersDoNotOverlap() {
        val first = treeUri("com.android.externalstorage.documents", "primary:Documents/CopySource")
        val second = treeUri("com.example.documents", "primary:Documents/CopySource")

        assertFalse(TreeRelationship.overlaps(first, second))
    }

    @Test
    fun missingSecondTreeDoesNotOverlap() {
        val first = treeUri("com.android.externalstorage.documents", "primary:Documents/CopySource")

        assertFalse(TreeRelationship.overlaps(first, null))
    }

    @Test
    fun malformedTreeFromSameProviderFailsClosed() {
        val valid = treeUri("com.android.externalstorage.documents", "primary:Documents/CopySource")
        val malformed = "content://com.android.externalstorage.documents/not-a-tree"

        assertTrue(TreeRelationship.overlaps(valid, malformed))
    }

    private fun treeUri(authority: String, documentId: String): String =
        "content://$authority/tree/${android.net.Uri.encode(documentId)}"
}
