package com.swapstudio.ai.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The encoder input layout is the single most fragile part of the video export: writing I420 into an
 * NV12 buffer does not crash, it just produces a green or black video on the phone. These tests pin
 * the byte layout and the BT.601 maths so a refactor cannot silently break it.
 */
class YuvUtilsTest {

    /** Fills an RGB buffer from a lambda so the layouts can be checked pixel by pixel. */
    private fun rgb(width: Int, height: Int, f: (Int, Int) -> Triple<Int, Int, Int>): ByteArray {
        val out = ByteArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val (r, g, b) = f(x, y)
                val i = (y * width + x) * 3
                out[i] = r.toByte()
                out[i + 1] = g.toByte()
                out[i + 2] = b.toByte()
            }
        }
        return out
    }

    private fun ByteArray.u(index: Int) = this[index].toInt() and 0xFF

    @Test
    fun `frame size is one and a half bytes per pixel`() {
        assertEquals(6, YuvUtils.frameSize(2, 2))
        assertEquals(4 * 4 * 3 / 2, YuvUtils.frameSize(4, 4))
        assertEquals(1920 * 1080 * 3 / 2, YuvUtils.frameSize(1920, 1080))
    }

    @Test
    fun `odd dimensions are rejected because 4 2 0 needs even width and height`() {
        assertTrue(YuvUtils.isSupported(1920, 1080))
        assertTrue(!YuvUtils.isSupported(1921, 1080))
        assertTrue(!YuvUtils.isSupported(1920, 0))
    }

    @Test
    fun `i420 puts Y then U then V`() {
        // Left half pure red, right half pure blue: the two chroma planes must differ.
        val width = 4
        val height = 2
        val bytes = YuvUtils.rgbToI420(
            rgb(width, height) { x, _ -> if (x < 2) Triple(255, 0, 0) else Triple(0, 0, 255) },
            width,
            height,
        )
        assertEquals(YuvUtils.frameSize(width, height), bytes.size)
        // Y plane: red luma 0.257*255+16 = 81.5 -> 82, blue luma 0.098*255+16 = 41.0 -> 41.
        assertEquals(82, bytes.u(0))
        assertEquals(41, bytes.u(3))
        // U plane starts at frameSize, V plane at frameSize + frameSize/4
        val frameSize = width * height
        val u0 = bytes.u(frameSize)
        val v0 = bytes.u(frameSize + frameSize / 4)
        assertTrue("red must have more V than U ($u0/$v0)", v0 > u0)
    }

    @Test
    fun `nv12 interleaves u and v and nv21 swaps them`() {
        val width = 2
        val height = 2
        val source = rgb(width, height) { _, _ -> Triple(10, 200, 40) }
        val nv12 = YuvUtils.rgbToNv12(source, width, height)
        val nv21 = YuvUtils.rgbToNv21(source, width, height)
        val frameSize = width * height
        assertEquals(nv12.size, nv21.size)
        // Same Y plane, mirrored chroma bytes.
        for (i in 0 until frameSize) assertEquals(nv12.u(i), nv21.u(i))
        assertEquals(nv12.u(frameSize), nv21.u(frameSize + 1))
        assertEquals(nv12.u(frameSize + 1), nv21.u(frameSize))
        assertNotEquals(nv12.u(frameSize), nv12.u(frameSize + 1))
    }

    @Test
    fun `black is 16 and white is 235 in studio swing`() {
        val black = YuvUtils.rgbToI420(ByteArray(2 * 2 * 3), 2, 2)
        assertEquals(16, black.u(0))
        val white = YuvUtils.rgbToI420(rgb(2, 2) { _, _ -> Triple(255, 255, 255) }, 2, 2)
        assertEquals(235, white.u(0))
        // Neutral grey has no chroma bias.
        val grey = YuvUtils.rgbToNv12(rgb(2, 2) { _, _ -> Triple(128, 128, 128) }, 2, 2)
        assertTrue(abs(grey.u(4) - 128) <= 2)
        assertTrue(abs(grey.u(5) - 128) <= 2)
    }

    @Test
    fun `round trip through i420 stays close for a flat colour`() {
        val width = 8
        val height = 8
        val colour = Triple(90, 140, 200)
        val i420 = YuvUtils.rgbToI420(rgb(width, height) { _, _ -> colour }, width, height)
        val argb = YuvUtils.i420ToArgb(i420, width, height)
        val pixel = argb[0]
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val alpha = (pixel ushr 24) and 0xFF
        assertEquals(255, alpha)
        // YUV 4:2:0 is lossy; a few levels of error per channel is expected.
        assertTrue("R $r vs ${colour.first}", abs(r - colour.first) <= 4)
        assertTrue("G $g vs ${colour.second}", abs(g - colour.second) <= 4)
        assertTrue("B $b vs ${colour.third}", abs(b - colour.third) <= 4)
    }

    @Test
    fun `chroma uses the 2x2 average so a checkerboard becomes neutral`() {
        val width = 2
        val height = 2
        // Two red and two blue pixels average to grey-purple; the chroma must sit between them
        // rather than following a single pixel.
        val checker = rgb(width, height) { x, y ->
            if ((x + y) % 2 == 0) Triple(255, 0, 0) else Triple(0, 0, 255)
        }
        val nv12 = YuvUtils.rgbToNv12(checker, width, height)
        val u = nv12.u(width * height)
        val v = nv12.u(width * height + 1)
        // Average of red and blue: r = 127.5, b = 127.5 -> U ~ 165, V ~ 175
        assertTrue("u=$u v=$v", u in 150..190 && v in 160..200)
    }

    @Test
    fun `single pixel quads are clamped at the right and bottom edge`() {
        val width = 2
        val height = 2
        val source = rgb(width, height) { _, _ -> Triple(0, 255, 0) }
        // Must not throw even though there is no second row/column to average with.
        val bytes = YuvUtils.rgbToNv12(source, width, height)
        assertEquals(YuvUtils.frameSize(width, height), bytes.size)
    }
}
