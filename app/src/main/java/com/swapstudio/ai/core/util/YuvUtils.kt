package com.swapstudio.ai.core.util

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * RGB <-> YUV 4:2:0 conversion in the layouts `MediaCodec` encoders accept.
 *
 * The encoder on a real phone almost never takes the format you would like: flagship SoCs advertise
 * semi-planar NV12 (or Qualcomm's alias for it), some advertise plain I420, and some advertise
 * `COLOR_FormatYUV420Flexible` and then accept planar data. Writing the wrong layout does not throw -
 * it produces a green or black video - so the conversion lives here, is unit tested, and the caller
 * picks the function that matches the format the encoder actually advertised.
 *
 * All functions take **interleaved RGB888** (three bytes per pixel, row major), which is what
 * `Mat.get(0, 0, byteArray)` returns for a `CV_8UC3` OpenCV mat, and the returned arrays are the exact
 * bytes to `put()` into the encoder input buffer.
 *
 * The maths is BT.601 with studio swing (16..235 / 16..240), the range Android's own
 * `YuvImage`/`ImageFormat` use for camera and video, and applies 2x2 box averaging for chroma so
 * edges do not shimmer.
 */
object YuvUtils {

    /** Bytes needed for one 4:2:0 frame of [width] x [height]. */
    fun frameSize(width: Int, height: Int): Int = width * height * 3 / 2

    /** True when the image dimensions are valid 4:2:0 input (even width and height, non-empty). */
    fun isSupported(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0

    /** Interleaved RGB888 -> planar I420: the full Y plane, then U, then V. */
    fun rgbToI420(rgb: ByteArray, width: Int, height: Int): ByteArray {
        val frameSize = width * height
        val out = ByteArray(frameSize + frameSize / 2)
        for (i in 0 until frameSize) {
            out[i] = luma(rgb, i * 3)
        }
        var u = frameSize
        var v = frameSize + frameSize / 4
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                val (r, g, b) = averageQuad(rgb, width, height, x, y)
                out[u++] = chromaU(r, g, b)
                out[v++] = chromaV(r, g, b)
            }
        }
        return out
    }

    /** Interleaved RGB888 -> NV12: the full Y plane, then interleaved U,V pairs. */
    fun rgbToNv12(rgb: ByteArray, width: Int, height: Int): ByteArray {
        val frameSize = width * height
        val out = ByteArray(frameSize + frameSize / 2)
        for (i in 0 until frameSize) {
            out[i] = luma(rgb, i * 3)
        }
        var uv = frameSize
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                val (r, g, b) = averageQuad(rgb, width, height, x, y)
                out[uv++] = chromaU(r, g, b)
                out[uv++] = chromaV(r, g, b)
            }
        }
        return out
    }

    /**
     * Interleaved RGB888 -> NV21: the full Y plane, then interleaved **V,U** pairs.
     *
     * Only needed for the handful of encoders that publish
     * `COLOR_FormatYUV420SemiPlanar` but read the swizzled (V-first) variant.
     */
    fun rgbToNv21(rgb: ByteArray, width: Int, height: Int): ByteArray {
        val frameSize = width * height
        val out = ByteArray(frameSize + frameSize / 2)
        for (i in 0 until frameSize) {
            out[i] = luma(rgb, i * 3)
        }
        var uv = frameSize
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                val (r, g, b) = averageQuad(rgb, width, height, x, y)
                out[uv++] = chromaV(r, g, b)
                out[uv++] = chromaU(r, g, b)
            }
        }
        return out
    }

    // -------------------------------------------------------------------------------------------
    // Sampling helpers (public so the tests can pin the coefficients)
    // -------------------------------------------------------------------------------------------

    /** BT.601 studio-swing luma for one RGB triple at [index] in an interleaved buffer. */
    fun luma(rgb: ByteArray, index: Int): Byte {
        val r = rgb[index].toInt() and 0xFF
        val g = rgb[index + 1].toInt() and 0xFF
        val b = rgb[index + 2].toInt() and 0xFF
        return (0.257f * r + 0.504f * g + 0.098f * b + 16f).roundToInt().coerceIn(0, 255).toByte()
    }

    fun chromaU(r: Float, g: Float, b: Float): Byte =
        (-0.148f * r - 0.291f * g + 0.439f * b + 128f).roundToInt().coerceIn(0, 255).toByte()

    fun chromaV(r: Float, g: Float, b: Float): Byte =
        (0.439f * r - 0.368f * g - 0.071f * b + 128f).roundToInt().coerceIn(0, 255).toByte()

    /** Mean RGB of the 2x2 block whose top-left corner is ([x], [y]), clamped at the edges. */
    fun averageQuad(rgb: ByteArray, width: Int, height: Int, x: Int, y: Int): Triple<Float, Float, Float> {
        var r = 0
        var g = 0
        var b = 0
        for (dy in 0 until 2) {
            val sy = min(y + dy, height - 1)
            for (dx in 0 until 2) {
                val sx = min(x + dx, width - 1)
                val i = (sy * width + sx) * 3
                r += rgb[i].toInt() and 0xFF
                g += rgb[i + 1].toInt() and 0xFF
                b += rgb[i + 2].toInt() and 0xFF
            }
        }
        return Triple(r / 4f, g / 4f, b / 4f)
    }

    // -------------------------------------------------------------------------------------------
    // The reverse direction, used by the video decoder fallback and by the tests.
    // -------------------------------------------------------------------------------------------

    /** Planar I420 bytes -> interleaved ARGB8888 pixels (one Int per pixel). */
    fun i420ToArgb(bytes: ByteArray, width: Int, height: Int): IntArray {
        val frameSize = width * height
        val uOffset = frameSize
        val vOffset = frameSize + frameSize / 4
        val pixels = IntArray(frameSize)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val uv = (y / 2) * (width / 2) + (x / 2)
                val yValue = bytes[y * width + x].toInt() and 0xFF
                val uValue = bytes[uOffset + uv].toInt() and 0xFF
                val vValue = bytes[vOffset + uv].toInt() and 0xFF
                pixels[y * width + x] = yuvToArgb(yValue, uValue, vValue)
            }
        }
        return pixels
    }

    /** BT.601 studio swing YUV -> opaque ARGB. */
    fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        val yf = (y - 16).coerceAtLeast(0) * 1.164f
        val uf = u - 128
        val vf = v - 128
        val r = (yf + 1.596f * vf).roundToInt().coerceIn(0, 255)
        val g = (yf - 0.391f * uf - 0.813f * vf).roundToInt().coerceIn(0, 255)
        val b = (yf + 2.018f * uf).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
