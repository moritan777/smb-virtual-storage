package dev.networkstorage.data.copy

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

interface CopySourceTree {
    suspend fun listFiles(treeUri: String, includeSubfolders: Boolean): List<CopySourceFile>
}

/**
 * SAF-backed source reader for Copy to SMB.
 *
 * The persisted value is a content tree URI only. Raw filesystem paths are never accepted or
 * produced. Relative paths are derived from SAF display names and validated as individual path
 * segments before being passed to the transfer orchestrator.
 */
class SafCopySourceTree @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageGuard: CopySourceStorageGuard,
) : CopySourceTree {
    override suspend fun listFiles(
        treeUri: String,
        includeSubfolders: Boolean,
    ): List<CopySourceFile> = withContext(Dispatchers.IO) {
        storageGuard.requireSafe(treeUri)
        val uri = Uri.parse(treeUri)
        require(uri.scheme == "content") { "Copy source must be a SAF content URI" }

        val root = requireNotNull(DocumentFile.fromTreeUri(context, uri)) {
            "Unable to open SAF source tree"
        }
        require(root.exists() && root.isDirectory) { "SAF source tree is unavailable" }

        val result = mutableListOf<CopySourceFile>()
        collectFiles(root, parentRelativePath = "", includeSubfolders = includeSubfolders, result = result)
        result
    }

    private suspend fun collectFiles(
        directory: DocumentFile,
        parentRelativePath: String,
        includeSubfolders: Boolean,
        result: MutableList<CopySourceFile>,
    ) {
        coroutineContext.ensureActive()
        for (child in directory.listFiles()) {
            coroutineContext.ensureActive()
            val name = requireNotNull(child.name) { "SAF document has no display name" }
            SourceRelativePath.validateSegment(name)
            val relativePath = SourceRelativePath.join(parentRelativePath, name)

            when {
                child.isDirectory && includeSubfolders -> collectFiles(
                    directory = child,
                    parentRelativePath = relativePath,
                    includeSubfolders = true,
                    result = result,
                )
                child.isFile -> {
                    val childUri = child.uri
                    val size = querySize(childUri, child)
                    result += CopySourceFile(
                        relativePath = relativePath,
                        size = size,
                        openInput = {
                            context.contentResolver.openInputStream(childUri)
                                ?: throw FileNotFoundException("Unable to open SAF source document")
                        },
                    )
                }
            }
        }
    }

    private fun querySize(uri: Uri, document: DocumentFile): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                val value = cursor.getLong(index)
                if (value >= 0L) return value
            }
        }
        val fallback = document.length()
        require(fallback >= 0L) { "SAF source size is unavailable" }
        return fallback
    }
}

object SourceRelativePath {
    fun validateSegment(name: String) {
        require(name.isNotBlank()) { "Source document name must not be blank" }
        require('\u0000' !in name) { "NUL is not allowed in source document names" }
        require('/' !in name && '\\' !in name) { "Source document name must be one path segment" }
        require(name != "." && name != "..") { "Invalid source document name" }
    }

    fun join(parent: String, child: String): String {
        validateSegment(child)
        val normalizedParent = normalizeParent(parent)
        return listOf(normalizedParent, child).filter { it.isNotBlank() }.joinToString("/")
    }

    private fun normalizeParent(parent: String): String {
        if (parent.isBlank()) return ""
        val segments = parent.split('/')
        require(segments.none { it.isBlank() }) { "Invalid source relative path" }
        segments.forEach(::validateSegment)
        return segments.joinToString("/")
    }
}
