package dev.networkstorage.data.mirror

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorSyncPolicyTest {
    @Test fun onlyRemoteOnlyAndRemoteNewerAreDownloadCandidates() {
        assertTrue(MirrorSyncPolicy.canCopyRemoteToLocal(MirrorDiffState.REMOTE_ONLY))
        assertTrue(MirrorSyncPolicy.canCopyRemoteToLocal(MirrorDiffState.REMOTE_NEWER))
        assertFalse(MirrorSyncPolicy.canCopyRemoteToLocal(MirrorDiffState.SAME))
        assertFalse(MirrorSyncPolicy.canCopyRemoteToLocal(MirrorDiffState.LOCAL_ONLY))
        assertFalse(MirrorSyncPolicy.canCopyRemoteToLocal(MirrorDiffState.LOCAL_NEWER))
    }

    @Test fun nasDeletionNeverTurnsLocalOnlyFileIntoAutomaticDownloadCandidate() {
        val afterNasDeletion = MirrorDiffPolicy.classify(null, null, 1024, 5000)
        assertTrue(afterNasDeletion == MirrorDiffState.LOCAL_ONLY)
        assertFalse(MirrorSyncPolicy.canCopyRemoteToLocal(afterNasDeletion))
    }

    @Test fun localNewerMirrorIsNeverOverwrittenByAutomaticSync() {
        val localNewer = MirrorDiffPolicy.classify(1024, 1000, 2048, 5000)
        assertTrue(localNewer == MirrorDiffState.LOCAL_NEWER)
        assertFalse(MirrorSyncPolicy.canCopyRemoteToLocal(localNewer))
    }
}
