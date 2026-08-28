package dev.networkstorage.domain

/**
 * Pure Windows-style conflict-name generation for Copy to SMB KEEP_BOTH.
 *
 * Examples:
 * - photo.jpg -> photo (1).jpg
 * - archive.tar.gz -> archive.tar (1).gz
 * - README -> README (1)
 * - .nomedia -> .nomedia (1)
 *
 * Compound extensions follow the product contract by inserting the counter before the final
 * extension only. Dot files with no additional dot are treated as extensionless names.
 */
object WindowsConflictNaming {
    fun numbered(fileName: String, number: Int): String {
        require(number > 0) { "Number must be positive" }
        validateFileName(fileName)

        val finalDot = fileName.lastIndexOf('.')
        val hasExtension = finalDot > 0 && finalDot < fileName.lastIndex

        return if (hasExtension) {
            val stem = fileName.substring(0, finalDot)
            val extension = fileName.substring(finalDot)
            "$stem ($number)$extension"
        } else {
            "$fileName ($number)"
        }
    }

    /**
     * Returns the first unoccupied numbered candidate.
     *
     * This is only a pure candidate-selection helper. The SMB orchestration layer must re-check
     * destination availability immediately before promotion because another client can create the
     * same name after this function returns.
     */
    fun firstAvailable(fileName: String, exists: (String) -> Boolean): String {
        validateFileName(fileName)
        var number = 1
        while (true) {
            val candidate = numbered(fileName, number)
            if (!exists(candidate)) return candidate
            check(number < Int.MAX_VALUE) { "No conflict suffix remains" }
            number++
        }
    }

    private fun validateFileName(fileName: String) {
        require(fileName.isNotBlank()) { "File name must not be blank" }
        require('\u0000' !in fileName) { "NUL is not allowed" }
        require('/' !in fileName && '\\' !in fileName) { "File name must not contain path separators" }
        require(fileName != "." && fileName != "..") { "Invalid file name" }
    }
}
