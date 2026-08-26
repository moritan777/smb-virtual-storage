package dev.networkstorage.data.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorDiffPolicyTest {
    @Test fun classifiesPresenceAndDirection() {
        assertEquals(MirrorDiffState.REMOTE_ONLY, MirrorDiffPolicy.classify(10, 100, null, null))
        assertEquals(MirrorDiffState.LOCAL_ONLY, MirrorDiffPolicy.classify(null, null, 10, 100))
        assertEquals(MirrorDiffState.SAME, MirrorDiffPolicy.classify(10, 1000, 10, 2500))
        assertEquals(MirrorDiffState.REMOTE_NEWER, MirrorDiffPolicy.classify(11, 5000, 10, 1000))
        assertEquals(MirrorDiffState.LOCAL_NEWER, MirrorDiffPolicy.classify(10, 1000, 11, 5000))
    }

    @Test fun unknownTimestampUsesEqualSizeAsSame() {
        assertEquals(MirrorDiffState.SAME, MirrorDiffPolicy.classify(123, 0, 123, 9999))
    }
}
