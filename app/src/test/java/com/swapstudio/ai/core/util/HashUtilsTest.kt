package com.swapstudio.ai.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32

class HashUtilsTest {

    private val payload = "SwapStudio AI - on device face swap\n".repeat(64)

    /** Creates a throw-away file; JUnit 4 does not guarantee a temp dir rule order across APIs. */
    private fun newFile(name: String): File {
        val file = File.createTempFile("swapstudio-$name-", ".tmp")
        file.deleteOnExit()
        return file
    }

    @Test
    fun `sha256 matches the JCA digest of the same bytes`() {
        val file = newFile("weights.bin").apply { writeText(payload) }
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, HashUtils.sha256Hex(file))
        assertEquals(expected, HashUtils.sha256Hex(payload.toByteArray()))
    }

    @Test
    fun `crc32 uses the zlib polynomial FaceFusion publishes`() {
        val file = newFile("weights.bin").apply { writeText(payload) }
        val crc = CRC32().apply { update(payload.toByteArray()) }
        assertEquals(String.format("%08x", crc.value), HashUtils.crc32Hex(file))
    }

    @Test
    fun `verify accepts both digests and rejects a mismatch`() {
        val file = newFile("weights.bin").apply { writeText(payload) }
        val sha = HashUtils.sha256Hex(file)
        val crc = HashUtils.crc32Hex(file)
        assertTrue(HashUtils.verify(file, sha, isSha256 = true))
        assertTrue(HashUtils.verify(file, sha.uppercase(), isSha256 = true))
        assertTrue(HashUtils.verify(file, crc, isSha256 = false))
        assertFalse(HashUtils.verify(file, "0".repeat(64), isSha256 = true))
    }

    @Test
    fun `verify never trusts a missing file or a blank digest`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "swapstudio-missing-${System.nanoTime()}.bin")
        assertFalse(HashUtils.verify(missing, "abc", isSha256 = true))
        val file = newFile("empty.bin")
        assertFalse(HashUtils.verify(file, "  ", isSha256 = false))
    }

    @Test
    fun `hashing streams large files without loading them`() {
        val file = newFile("large.bin")
        file.outputStream().use { out ->
            val chunk = ByteArray(1 shl 16) { (it % 251).toByte() }
            repeat(64) { out.write(chunk) }
        }
        assertEquals(64L * (1 shl 16), file.length())
        assertEquals(64, HashUtils.sha256Hex(file).length)
    }
}
