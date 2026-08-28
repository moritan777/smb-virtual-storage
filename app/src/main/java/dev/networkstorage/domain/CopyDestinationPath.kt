package dev.networkstorage.domain

/**
 * Pure Copy to SMB destination-path boundary policy.
 *
 * This policy is intentionally independent of the SMB implementation. Callers must validate and
 * normalize destination input before passing it to the scoped SMB write boundary.
 *
 * The empty string represents the configured connection root. Absolute paths, traversal, NUL,
 * drive-qualified paths, URI-like inputs, UNC-style inputs, and the reserved application backup
 * tree are rejected.
 */
object CopyDestinationPath {
    const val RESERVED_BACKUP_DIRECTORY = ".network-storage-backup"

    private val driveQualified = Regex("^[A-Za-z]:($|[\\\\/].*)")
    private val uriLike = Regex("^[A-Za-z][A-Za-z0-9+.-]*://.*")

    fun normalize(path: String): String {
        require('\u0000' !in path) { "NUL is not allowed" }
        require(!path.startsWith('/') && !path.startsWith('\\')) { "Destination must be relative" }
        require(!driveQualified.matches(path)) { "Drive-qualified destinations are not allowed" }
        require(!uriLike.matches(path)) { "URI destinations are not allowed" }

        val parts = path
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." }

        require(parts.none { it == ".." }) { "Destination escapes the configured root" }
        require(parts.none { it.equals(RESERVED_BACKUP_DIRECTORY, ignoreCase = true) }) {
            "Reserved backup tree cannot be selected as a normal destination"
        }

        return parts.joinToString("/")
    }

    /**
     * Joins a source-relative path beneath a configured Copy to SMB destination.
     *
     * Both inputs are normalized independently, then the combined result is validated again. The
     * final validation is deliberate: no future change to either input policy may accidentally
     * turn the join into a root escape or reserved-backup-tree destination.
     */
    fun join(destination: String, sourceRelativePath: String): String {
        val normalizedDestination = normalize(destination)
        val normalizedSource = normalizeSourceRelative(sourceRelativePath)
        return normalize(
            listOf(normalizedDestination, normalizedSource)
                .filter { it.isNotEmpty() }
                .joinToString("/"),
        )
    }

    private fun normalizeSourceRelative(path: String): String {
        require(path.isNotBlank()) { "Source-relative path must not be blank" }
        require('\u0000' !in path) { "NUL is not allowed" }
        require(!path.startsWith('/') && !path.startsWith('\\')) { "Source path must be relative" }
        require(!driveQualified.matches(path)) { "Drive-qualified source paths are not allowed" }
        require(!uriLike.matches(path)) { "URI source paths are not allowed" }

        val parts = path
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotEmpty() && it != "." }

        require(parts.isNotEmpty()) { "Source-relative path must name a file or directory" }
        require(parts.none { it == ".." }) { "Source path escapes the selected tree" }
        require(parts.none { it.equals(RESERVED_BACKUP_DIRECTORY, ignoreCase = true) }) {
            "Reserved backup tree is application-owned"
        }
        return parts.joinToString("/")
    }
}
