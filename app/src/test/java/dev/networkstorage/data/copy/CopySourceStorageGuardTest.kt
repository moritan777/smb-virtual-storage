package dev.networkstorage.data.copy

import org.junit.Assert.assertThrows
import org.junit.Test

class CopySourceStorageGuardTest {
    private val source = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FUpload"
    private val parent = "content://com.android.externalstorage.documents/tree/primary%3ADocuments"
    private val child = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FUpload%2FNested"
    private val separate = "content://com.android.externalstorage.documents/tree/primary%3ADownload"

    @Test fun rejectsSourceInsideCacheRoot() {
        assertThrows(IllegalArgumentException::class.java) {
            CopySourceStorageGuard.requireSafe(source, parent, null)
        }
    }

    @Test fun rejectsCacheRootInsideSource() {
        assertThrows(IllegalArgumentException::class.java) {
            CopySourceStorageGuard.requireSafe(source, child, null)
        }
    }

    @Test fun rejectsMirrorOverlap() {
        assertThrows(IllegalArgumentException::class.java) {
            CopySourceStorageGuard.requireSafe(source, null, source)
        }
    }

    @Test fun allowsSeparateTrees() {
        CopySourceStorageGuard.requireSafe(source, separate, null)
    }
}
