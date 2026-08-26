package dev.networkstorage.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class UserFacingErrorTest {
    @Test fun smbCategoriesRemainStable() {
        assertEquals(UserFacingError.AUTHENTICATION, UserFacingError.code(SmbFailure(NetworkError.AUTHENTICATION)))
        assertEquals(UserFacingError.HOST_NOT_FOUND, UserFacingError.code(SmbFailure(NetworkError.HOST_NOT_FOUND)))
        assertEquals(UserFacingError.TIMEOUT, UserFacingError.code(SmbFailure(NetworkError.TIMEOUT)))
        assertEquals(UserFacingError.REMOTE_NOT_FOUND, UserFacingError.code(SmbFailure(NetworkError.REMOTE_NOT_FOUND)))
    }

    @Test fun localFailureCodesAreNormalized() {
        assertEquals(UserFacingError.MIRROR_ROOT_UNCONFIGURED, UserFacingError.code(IllegalStateException("MIRROR_ROOT_UNCONFIGURED")))
        assertEquals(UserFacingError.SIZE_MISMATCH, UserFacingError.code(IllegalStateException("MIRROR_SIZE_MISMATCH")))
        assertEquals(UserFacingError.LOCAL_STORAGE, UserFacingError.code(IllegalStateException("MIRROR_PROMOTION_FAILED")))
    }

    @Test fun messagesNeverExposeBackendDetails() {
        assertEquals(
            "Authentication failed. Check the username and password.",
            UserFacingError.message(UserFacingError.AUTHENTICATION),
        )
        assertEquals(
            "The operation could not be completed.",
            UserFacingError.message("SOME_BACKEND_EXCEPTION"),
        )
    }
}
