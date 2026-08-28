package dev.networkstorage.data.copy

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Persists only read access for a user-selected Copy to SMB SAF source tree. */
class SafTreePermissionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun persistReadPermission(treeUri: String, resultFlags: Int) {
        val uri = Uri.parse(treeUri)
        require(uri.scheme == "content") { "Copy source must be a SAF content URI" }
        require(resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            "SAF result did not grant read permission"
        }
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
}
