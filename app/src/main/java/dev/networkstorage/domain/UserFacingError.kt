package dev.networkstorage.domain

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/** Stable error codes and user-facing copy shared by workers and UI. */
object UserFacingError {
    const val AUTHENTICATION = "AUTHENTICATION"
    const val HOST_NOT_FOUND = "HOST_NOT_FOUND"
    const val SHARE_NOT_FOUND = "SHARE_NOT_FOUND"
    const val CONNECTION = "CONNECTION"
    const val TIMEOUT = "TIMEOUT"
    const val REMOTE_NOT_FOUND = "REMOTE_NOT_FOUND"
    const val CREDENTIAL_UNAVAILABLE = "CREDENTIAL_UNAVAILABLE"
    const val CACHE_ROOT_UNCONFIGURED = "CACHE_ROOT_UNCONFIGURED"
    const val MIRROR_ROOT_UNCONFIGURED = "MIRROR_ROOT_UNCONFIGURED"
    const val FILE_EXCEEDS_CACHE_LIMIT = "FILE_EXCEEDS_CACHE_LIMIT"
    const val CACHE_LIMIT_CANNOT_BE_SATISFIED = "CACHE_LIMIT_CANNOT_BE_SATISFIED"
    const val LOCAL_STORAGE = "LOCAL_STORAGE"
    const val SIZE_MISMATCH = "SIZE_MISMATCH"
    const val UNKNOWN = "UNKNOWN"

    fun code(error: Throwable): String {
        val smb = error as? SmbFailure
        if (smb != null) return smb.category.name
        return when (error) {
            is UnknownHostException -> HOST_NOT_FOUND
            is SocketTimeoutException, is TimeoutException -> TIMEOUT
            is ConnectException -> CONNECTION
            else -> when (error.message) {
                CACHE_ROOT_UNCONFIGURED -> CACHE_ROOT_UNCONFIGURED
                MIRROR_ROOT_UNCONFIGURED -> MIRROR_ROOT_UNCONFIGURED
                FILE_EXCEEDS_CACHE_LIMIT -> FILE_EXCEEDS_CACHE_LIMIT
                CACHE_LIMIT_CANNOT_BE_SATISFIED -> CACHE_LIMIT_CANNOT_BE_SATISFIED
                "CREDENTIAL_UNAVAILABLE" -> CREDENTIAL_UNAVAILABLE
                "MIRROR_SIZE_MISMATCH", "CACHE_SIZE_MISMATCH" -> SIZE_MISMATCH
                "MIRROR_REPLACE_FAILED", "MIRROR_PROMOTION_FAILED" -> LOCAL_STORAGE
                else -> UNKNOWN
            }
        }
    }

    fun message(code: String): String = when (code) {
        AUTHENTICATION -> "Authentication failed. Check the username and password."
        HOST_NOT_FOUND -> "The network storage host could not be found."
        SHARE_NOT_FOUND -> "The SMB share could not be found."
        CONNECTION -> "Network storage is unreachable. Check the network connection."
        TIMEOUT -> "The network storage connection timed out."
        REMOTE_NOT_FOUND -> "The file no longer exists on the NAS."
        CREDENTIAL_UNAVAILABLE -> "Saved credentials are unavailable. Edit the connection and save the password again."
        CACHE_ROOT_UNCONFIGURED -> "Set the On-demand Cache folder in Settings."
        MIRROR_ROOT_UNCONFIGURED -> "Set the Mirror folder in Settings."
        FILE_EXCEEDS_CACHE_LIMIT -> "This file is larger than the cache limit."
        CACHE_LIMIT_CANNOT_BE_SATISFIED -> "Not enough removable cache space is available."
        LOCAL_STORAGE -> "Local storage could not be written. Check folder access and free space."
        SIZE_MISMATCH -> "The copied file was incomplete. Please try again."
        else -> "The operation could not be completed."
    }
}
