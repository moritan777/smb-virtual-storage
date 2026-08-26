package dev.networkstorage.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

class UserFacingErrorTest {
    @Test fun smbCategoriesRemainStable() {
        assertEquals(UserFacingError.AUTHENTICATION, UserFacingError.code(SmbFailure(NetworkError.AUTHENTICATION)))
        assertEquals(UserFacingError.HOST_NOT_FOUND, UserFacingError.code(SmbFailure(NetworkError.HOST_NOT_FOUND)))
        assertEquals(UserFacingError.CONNECTION, UserFacingError.code(SmbFailure(NetworkError.CONNECTION)))
        assertEquals(UserFacingError.TIMEOUT, UserFacingError.code(SmbFailure(NetworkError.TIMEOUT)))
        assertEquals(UserFacingError.REMOTE_NOT_FOUND, UserFacingError.code(SmbFailure(NetworkError.REMOTE_NOT_FOUND)))
    }

    @Test fun transportFailuresRemainUserSafe() {
        assertEquals(UserFacingError.HOST_NOT_FOUND, UserFacingError.code(UnknownHostException("private-hostname")))
        assertEquals(UserFacingError.CONNECTION, UserFacingError.code(ConnectException("connection refused at 192.0.2.1")))
        assertEquals(UserFacingError.TIMEOUT, UserFacingError.code(TimeoutException("backend timeout detail")))
    }

    @Test fun localFailureCodesAreNormalized() {
        assertEquals(UserFacingError.MIRROR_ROOT_UNCONFIGURED, UserFacingError.code(IllegalStateException("MIRROR_ROOT_UNCONFIGURED")))
        assertEquals(UserFacingError.CACHE_ROOT_UNCONFIGURED, UserFacingError.code(IllegalStateException("CACHE_ROOT_UNCONFIGURED")))
        assertEquals(UserFacingError.FILE_EXCEEDS_CACHE_LIMIT, UserFacingError.code(IllegalStateException("FILE_EXCEEDS_CACHE_LIMIT")))
        assertEquals(UserFacingError.CACHE_LIMIT_CANNOT_BE_SATISFIED, UserFacingError.code(IllegalStateException("CACHE_LIMIT_CANNOT_BE_SATISFIED")))
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
