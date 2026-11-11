package com.swapstudio.ai.core.util

import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Digest helpers used by the model downloader.
 *
 * Two levels of integrity checking are available:
 *  * **SHA-256** when the manifest publishes a digest (all official GitHub release assets, model
 *    hubs, ...). This is the strong check.
 *  * **CRC32** otherwise. FaceFusion publishes `<model>.hash` files containing the zlib CRC32 of the
 *    weights; we use the very same algorithm so those values can be copied into `models.json`
 *    verbatim.
 *
 * Both helpers stream the file, so a 350 MB download never has to fit in memory.
 */
object HashUtils {

    private const val BUFFER_SIZE = 256 * 1024

    /** Lower-case hex SHA-256 of [file]. */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Lower-case hex SHA-256 of a byte array (used for unit tests and small assets). */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** zlib CRC32 of [file], formatted as eight lower-case hex characters. */
    fun crc32Hex(file: File): String {
        val crc = CRC32()
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                crc.update(buffer, 0, read)
            }
        }
        return String.format("%08x", crc.value)
    }

    /** True when [expected] (SHA-256 or CRC32 hex) matches the file. */
    fun verify(file: File, expected: String, isSha256: Boolean): Boolean {
        if (!file.exists() || expected.isBlank()) return false
        val actual = if (isSha256) sha256Hex(file) else crc32Hex(file)
        return actual.equals(expected.trim(), ignoreCase = true)
    }
}
