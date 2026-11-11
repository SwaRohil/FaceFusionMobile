package com.swapstudio.ai.core.util

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfFloat
import org.opencv.core.Rect as CvRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Conversions and small helpers around OpenCV [Mat] objects.
 *
 * Colour-order convention used everywhere in this app:
 *  * `android.graphics.Bitmap` (ARGB_8888)  ->  [Mat] `CV_8UC4` in **RGBA** order (see
 *    [com.swapstudio.ai.core.util.BitmapUtils] for the conversion helpers).
 *  * "RGB Mat" / "BGR Mat" are `CV_8UC3` mats; anything that feeds a neural network is converted to
 *    BGR or RGB explicitly at the call site so the model contract is always visible.
 *  * Network tensors are `CV_32FC3` / `CV_32FC1` with values already normalised by the caller.
 *
 * Every function that allocates a [Mat] is documented as returning an owned instance: the caller is
 * responsible for [Mat.release]. Long running pipelines therefore wrap their scratch mats in
 * `try/finally`.
 */
object MatUtils {

    // ---------------------------------------------------------------------------------------
    // Colour-order normalisation
    // ---------------------------------------------------------------------------------------

    /** Normalises any 8-bit Mat to an owned RGBA `CV_8UC4` mat. */
    fun toRgba8u(src: Mat): Mat {
        val rgba = Mat()
        when {
            src.type() == CvType.CV_8UC4 -> src.copyTo(rgba)
            src.type() == CvType.CV_8UC3 -> Imgproc.cvtColor(src, rgba, Imgproc.COLOR_RGB2RGBA)
            src.type() == CvType.CV_8UC1 -> Imgproc.cvtColor(src, rgba, Imgproc.COLOR_GRAY2RGBA)
            src.type() == CvType.CV_8SC4 || src.type() == CvType.CV_32FC4 -> {
                src.convertTo(rgba, CvType.CV_8UC4)
            }
            else -> error("Unsupported Mat type for bitmap conversion: ${src.type()}")
        }
        return rgba
    }

    /** Deep copy of [src] as an owned mat (sugar so pipelines stay readable). */
    fun copy(src: Mat): Mat = src.clone()

    /** Ensures a mat is CV_8UC3 RGB with the requested size; always returns an owned mat. */
    fun ensureRgb(src: Mat, width: Int, height: Int): Mat {
        val rgb = when {
            src.type() == CvType.CV_8UC3 -> src.clone()
            src.type() == CvType.CV_8UC4 -> Mat().also { Imgproc.cvtColor(src, it, Imgproc.COLOR_RGBA2RGB) }
            src.type() == CvType.CV_8UC1 -> Mat().also { Imgproc.cvtColor(src, it, Imgproc.COLOR_GRAY2RGB) }
            else -> Mat().also { src.convertTo(it, CvType.CV_8UC3) }
        }
        if (rgb.cols() != width || rgb.rows() != height) {
            val resized = Mat()
            Imgproc.resize(rgb, resized, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            rgb.release()
            return resized
        }
        return rgb
    }

    // ---------------------------------------------------------------------------------------
    // Float / tensor helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Converts an RGB `CV_8UC3` mat into a planar NCHW float buffer, optionally normalised with
     * `(value * scale - mean[i]) * std[i]` per channel, and optionally in BGR channel order.
     */
    fun toNchwFloat(
        rgb: Mat,
        mean: FloatArray = floatArrayOf(0f, 0f, 0f),
        std: FloatArray = floatArrayOf(1f, 1f, 1f),
        scale: Float = 1f / 255f,
        bgr: Boolean = false,
    ): FloatArray {
        val width = rgb.cols()
        val height = rgb.rows()
        require(rgb.type() == CvType.CV_8UC3) { "toNchwFloat expects CV_8UC3" }
        val out = FloatArray(3 * width * height)
        val plane = width * height
        val data = ByteArray(plane * 3)
        rgb.get(0, 0, data)
        for (i in 0 until plane) {
            val r = data[i * 3].toInt() and 0xFF
            val g = data[i * 3 + 1].toInt() and 0xFF
            val b = data[i * 3 + 2].toInt() and 0xFF
            if (bgr) {
                out[i] = (b * scale - mean[0]) * std[0]
                out[plane + i] = (g * scale - mean[1]) * std[1]
                out[2 * plane + i] = (r * scale - mean[2]) * std[2]
            } else {
                out[i] = (r * scale - mean[0]) * std[0]
                out[plane + i] = (g * scale - mean[1]) * std[1]
                out[2 * plane + i] = (b * scale - mean[2]) * std[2]
            }
        }
        return out
    }

    /** Fast NCHW float -> interleaved [r,g,b] bytes mat (`CV_8UC3`, RGB order). */
    fun nchwFloatToRgb(input: FloatArray, width: Int, height: Int, bgr: Boolean = true): Mat {
        val mat = Mat(height, width, CvType.CV_8UC3)
        val plane = width * height
        val data = ByteArray(plane * 3)
        for (i in 0 until plane) {
            val c0 = ((input[i] * 255f) + 0.5f).toInt().coerceIn(0, 255)
            val c1 = ((input[plane + i] * 255f) + 0.5f).toInt().coerceIn(0, 255)
            val c2 = ((input[2 * plane + i] * 255f) + 0.5f).toInt().coerceIn(0, 255)
            // Network channels are RGB; convert to the requested storage order.
            data[i * 3] = (if (bgr) c2 else c0).toByte()
            data[i * 3 + 1] = c1.toByte()
            data[i * 3 + 2] = (if (bgr) c0 else c2).toByte()
        }
        mat.put(0, 0, data)
        return mat
    }

    /** Converts a float mat (any channel count) into an owned `[H,W,C]` CHW-plane float array. */
    fun matToChwFloat(src: Mat, scale: Float = 1f, mean: FloatArray? = null): FloatArray {
        val channels = src.channels()
        val plane = src.cols() * src.rows()
        val out = FloatArray(channels * plane)
        val pixel = FloatArray(channels)
        for (y in 0 until src.rows()) {
            for (x in 0 until src.cols()) {
                src.get(y, x, pixel)
                for (c in 0 until channels) {
                    val m = mean?.getOrElse(c) { 0f } ?: 0f
                    out[c * plane + y * src.cols() + x] = pixel[c] * scale - m
                }
            }
        }
        return out
    }

    /** Wraps a float array into a `[rows, cols]` single channel Mat. */
    fun floatArrayToMat(values: FloatArray, rows: Int, cols: Int): Mat {
        val mat = Mat(rows, cols, CvType.CV_32FC1)
        mat.put(0, 0, values)
        return mat
    }

    /** Reads a single channel `CV_32FC1` Mat into a float array (row major). */
    fun matToFloatArray(src: Mat): FloatArray {
        val out = FloatArray(src.rows() * src.cols())
        src.get(0, 0, out)
        return out
    }

    // ---------------------------------------------------------------------------------------
    // Statistics used by the colour pipeline
    // ---------------------------------------------------------------------------------------

    /** Per-channel mean and standard deviation restricted to [mask] (CV_8UC1, 0..255). */
    fun maskedMeanStd(lab: Mat, mask: Mat?): Pair<DoubleArray, DoubleArray> {
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(lab, mean, std, mask)
        return mean.toArray() to std.toArray()
    }

    /** Mean of a single channel mat inside the mask. */
    fun maskedMean(channel: Mat, mask: Mat?): Double = Core.mean(channel, mask).`val`[0]

    /** Normalises a mask to 0..255 `CV_8UC1`, always returning an owned mat. */
    fun normaliseMask(mask: Mat): Mat {
        val out = Mat()
        when (mask.type()) {
            CvType.CV_8UC1 -> mask.copyTo(out)
            CvType.CV_32FC1 -> Core.normalize(mask, out, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8UC1)
            else -> mask.convertTo(out, CvType.CV_8UC1)
        }
        return out
    }

    /** Feathers (blurs) a `CV_8UC1` mask so pasted edges never produce a visible seam. */
    fun feather(mask: Mat, radius: Int): Mat {
        val out = Mat()
        val k = radius * 2 + 1
        Imgproc.GaussianBlur(mask, out, Size(k.toDouble(), k.toDouble()), 0.0)
        return out
    }

    /** Erodes a `CV_8UC1` mask by [pixels], used to pull the skin mask inside the face outline. */
    fun erode(mask: Mat, pixels: Int): Mat {
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size((pixels * 2 + 1).toDouble(), (pixels * 2 + 1).toDouble()))
        val out = Mat()
        Imgproc.erode(mask, out, kernel)
        kernel.release()
        return out
    }

    /** Dilates a `CV_8UC1` mask by [pixels]. */
    fun dilate(mask: Mat, pixels: Int): Mat {
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size((pixels * 2 + 1).toDouble(), (pixels * 2 + 1).toDouble()))
        val out = Mat()
        Imgproc.dilate(mask, out, kernel)
        kernel.release()
        return out
    }

    // ---------------------------------------------------------------------------------------
    // Region helpers
    // ---------------------------------------------------------------------------------------

    /** Clamps a rectangle (x, y, w, h) to the bounds of a [width] x [height] image. */
    fun clampRect(x: Int, y: Int, w: Int, h: Int, width: Int, height: Int): IntArray {
        val left = x.coerceIn(0, width - 1)
        val top = y.coerceIn(0, height - 1)
        val right = (x + w).coerceIn(left + 1, width)
        val bottom = (y + h).coerceIn(top + 1, height)
        return intArrayOf(left, top, right - left, bottom - top)
    }

    /** Crops a mat with automatic bounds clamping and returns an owned copy. */
    fun crop(src: Mat, x: Int, y: Int, w: Int, h: Int): Mat {
        val r = clampRect(x, y, w, h, src.cols(), src.rows())
        val out = Mat(src, CvRect(r[0], r[1], r[2], r[3]))
        return out.clone()
    }

    /** Bilinear resize into an owned mat. */
    fun resize(src: Mat, width: Int, height: Int, interpolation: Int = Imgproc.INTER_LINEAR): Mat {
        val out = Mat()
        Imgproc.resize(src, out, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, interpolation)
        return out
    }

    // ---------------------------------------------------------------------------------------
    // Small scalar helpers
    // ---------------------------------------------------------------------------------------

    /** Returns a `[0,1]` coverage value of finite (non-NaN) entries of a float array. */
    fun finiteRatio(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        var count = 0
        for (v in values) if (!v.isNaN() && !v.isInfinite()) count++
        return count.toFloat() / values.size
    }

    /** Wraps a direct [ByteBuffer] so the JNI calls receive native memory (no copies on the JVM). */
    fun directBufferOf(sizeBytes: Int): ByteBuffer =
        ByteBuffer.allocateDirect(sizeBytes).order(ByteOrder.nativeOrder())

    /** Converts a `CV_8UC1` mat to a normalised probability mat (`CV_32FC1`, 0..1). */
    fun maskToProbability(mask: Mat): Mat {
        val out = Mat()
        mask.convertTo(out, CvType.CV_32FC1, 1.0 / 255.0)
        return out
    }

    /** Utility for tests: fills an RGB `CV_8UC3` mat with a solid colour. */
    fun solid(width: Int, height: Int, r: Int, g: Int, b: Int): Mat =
        Mat(height, width, CvType.CV_8UC3, Scalar(r.toDouble(), g.toDouble(), b.toDouble()))

    /** Median of a float array (used by the grain estimator). */
    fun median(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        val copy = values.copyOf()
        copy.sort()
        val mid = copy.size / 2
        return if (copy.size % 2 == 0) (copy[mid - 1] + copy[mid]) / 2f else copy[mid]
    }

    /** Standard deviation of a float array. */
    fun stdDev(values: FloatArray): Float {
        if (values.size < 2) return 0f
        var sum = 0.0
        for (v in values) sum += v
        val mean = sum / values.size
        var acc = 0.0
        for (v in values) {
            val d = v - mean
            acc += d * d
        }
        return kotlin.math.sqrt(acc / (values.size - 1)).toFloat()
    }

    /** Histogram (256 bins) of a single channel 8-bit mat, normalised to sum 1. */
    fun normalisedHistogram(channel: Mat, mask: Mat?): FloatArray {
        val hist = Mat()
        val maskMat = mask ?: Mat()
        Imgproc.calcHist(
            listOf(channel),
            org.opencv.core.MatOfInt(0),
            maskMat,
            hist,
            org.opencv.core.MatOfInt(256),
            org.opencv.core.MatOfFloat(0f, 256f),
        )
        val values = FloatArray(256)
        val buffer = FloatArray(256)
        hist.get(0, 0, buffer)
        var total = 0f
        for (v in buffer) total += v
        if (total <= 0f) return FloatArray(256) { 1f / 256f }
        for (i in 0 until 256) values[i] = buffer[i] / total
        hist.release()
        return values
    }

    /** Builds an owned single-channel 8-bit mat from a byte array. */
    fun maskFromBytes(bytes: ByteArray, width: Int, height: Int): Mat {
        val mat = Mat(height, width, CvType.CV_8UC1)
        mat.put(0, 0, bytes)
        return mat
    }

    /** Builds an owned float mat of the given shape from a float array. */
    fun floatMat(values: FloatArray, width: Int, height: Int, channels: Int = 1): Mat {
        val mat = Mat(height, width, CvType.CV_32FC(channels))
        mat.put(0, 0, values)
        return mat
    }

    /** Integral image free box blur of a float mat, used by the smoke/flare estimators. */
    fun blurFloat(src: Mat, radius: Int): Mat {
        val out = Mat()
        Imgproc.blur(src, out, Size((radius * 2 + 1).toDouble(), (radius * 2 + 1).toDouble()))
        return out
    }

    /** Clips a float array in place to `[lo, hi]`. */
    fun clip(values: FloatArray, lo: Float, hi: Float): FloatArray {
        for (i in values.indices) values[i] = values[i].coerceIn(lo, hi)
        return values
    }

    /** Converts a [MatOfFloat] to a float array. */
    fun toFloatArray(mat: MatOfFloat): FloatArray = mat.toArray()
}
