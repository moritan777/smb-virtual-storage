package dev.networkstorage.domain

object RemotePath {
    fun normalize(path: String): String {
        require('\u0000' !in path) { "NUL is not allowed" }
        require(!path.startsWith('/') && !path.startsWith('\\')) { "Path must be relative" }
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." }) { "Path escapes the configured root" }
        return parts.joinToString("/")
    }

    fun join(parent: String, child: String): String {
        require('/' !in child && '\\' !in child && child !in setOf(".", "..")) { "Invalid child name" }
        return normalize(listOf(parent, child).filter { it.isNotBlank() }.joinToString("/"))
    }

    fun parent(path: String): String = normalize(path).substringBeforeLast('/', "")
}
