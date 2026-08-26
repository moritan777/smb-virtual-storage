package dev.networkstorage.data.cache

import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

class SizeMismatchException(expected: Long, actual: Long) : Exception("Expected $expected bytes but copied $actual")

object DownloadCopier {
    suspend fun copy(input: InputStream, output: OutputStream, expected: Long, progress: suspend (Long) -> Unit = {}): Long {
        require(expected >= 0)
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            copied = Math.addExact(copied, read.toLong())
            progress(copied)
        }
        output.flush()
        if (copied != expected) throw SizeMismatchException(expected, copied)
        return copied
    }
}
