package com.swapstudio.ai.core.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/**
 * Bitmap <-> Mat helpers that need the Android framework.
 *
 * Kept apart from [MatUtils] on purpose: everything in [MatUtils] is pure OpenCV so it can be unit
 * tested on a plain JVM with the desktop OpenCV natives, while this file can only run on a device.
 *
 * Colour-order convention: [Utils.bitmapToMat] produces `CV_8UC4` in **RGBA** order, and every `Mat`
 * that is handed to [Utils.matToBitmap] is expected to be RGBA as well.
 */
object BitmapUtils {

    /** Converts a Bitmap into an owned RGBA `CV_8UC4` Mat. */
    fun bitmapToRgba(bitmap: Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        return mat
    }
    /** Converts a Bitmap into an owned RGB `CV_8UC3` Mat (alpha dropped). */
    fun bitmapToRgb(bitmap: Bitmap): Mat {
        val rgba = bitmapToRgba(bitmap)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        return rgb
    }
    /** Converts a Bitmap into an owned BGR `CV_8UC3` Mat. */
    fun bitmapToBgr(bitmap: Bitmap): Mat {
        val rgb = bitmapToRgb(bitmap)
        Imgproc.cvtColor(rgb, rgb, Imgproc.COLOR_RGB2BGR)
        return rgb
    }
    /**
     * Converts an 8-bit Mat (1, 3 or 4 channels, RGB/BGR/gray) into an ARGB_8888 Bitmap.
     * The input is never modified.
     */
    fun toBitmap(src: Mat): Bitmap {
        val rgba = MatUtils.toRgba8u(src)
        val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)
        rgba.release()
        return bitmap
    }
    /** Renders an owned RGB `CV_8UC3` Mat into a Bitmap. */
    fun rgbToBitmap(rgb: Mat): Bitmap {
        val rgba = Mat()
        Imgproc.cvtColor(rgb, rgba, Imgproc.COLOR_RGB2RGBA)
        val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)
        rgba.release()
        return bitmap
    }
    /** Draws the "AI Generated" label that every visible export carries. */
    fun drawWatermark(target: Bitmap, text: String, alpha: Int = 150): Bitmap {
        val mutable = if (target.isMutable) target else target.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val shortest = minOf(mutable.width, mutable.height).toFloat()
        val textSize = (shortest * 0.035f).coerceAtLeast(14f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            this.alpha = alpha
            isFakeBoldText = true
            setShadowLayer(textSize * 0.12f, 0f, textSize * 0.05f, Color.argb(160, 0, 0, 0))
        }
        val pad = (shortest * 0.02f).coerceAtLeast(8f)
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val x = mutable.width - bounds.width() - pad
        val y = mutable.height - pad
        canvas.drawText(text, x, y, paint)
        return mutable
    }
    /** Builds an ARGB_8888 Bitmap from an int array (used by the YUV converters). */
    fun argbArrayToBitmap(pixels: IntArray, width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)

    /**
     * Converts a `CV_8UC1` mask into an ARGB bitmap tinted with [color] and [alpha], for the mesh /
     * region overlays in the UI.
     */
    fun maskToOverlayBitmap(mask: Mat, color: Int, alpha: Float): Bitmap {
        val width = mask.cols()
        val height = mask.rows()
        val bytes = ByteArray(width * height)
        mask.get(0, 0, bytes)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val a = (alpha * 255).roundToInt().coerceIn(0, 255)
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val v = bytes[i].toInt() and 0xFF
            pixels[i] = if (v < 8) 0 else Color.argb((a * v) / 255, r, g, b)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
    /** ARGB Bitmap -> interleaved `[r,g,b]` bytes, the layout the MediaCodec YUV writer wants. */
    fun bitmapToRgbBytes(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = ByteArray(width * height * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            out[i * 3] = ((p shr 16) and 0xFF).toByte()
            out[i * 3 + 1] = ((p shr 8) and 0xFF).toByte()
            out[i * 3 + 2] = (p and 0xFF).toByte()
        }
        return out
    }
}
