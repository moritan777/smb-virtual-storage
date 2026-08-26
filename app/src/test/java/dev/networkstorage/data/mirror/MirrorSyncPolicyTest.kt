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
}
