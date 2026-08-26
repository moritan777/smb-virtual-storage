package dev.networkstorage.presentation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

class ExternalOpenService @Inject constructor(@ApplicationContext private val context: Context) {
    fun buildIntent(uri: Uri, fileName: String): Intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(fileName)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    fun open(uri: Uri, fileName: String): Boolean = try {
        context.startActivity(buildIntent(uri, fileName))
        true
    } catch (_: ActivityNotFoundException) { false }

    fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "cbz", "zip" -> "application/zip"
        "pdf" -> "application/pdf"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }
}
