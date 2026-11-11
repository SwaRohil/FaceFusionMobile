package com.swapstudio.ai.core.swap

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.util.Log
import com.swapstudio.ai.core.geometry.FaceWarp
import com.swapstudio.ai.core.inference.OrtEngine
import com.swapstudio.ai.core.inference.SessionSpec
import com.swapstudio.ai.core.models.ModelManager
import com.swapstudio.ai.core.util.MatUtils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Hyper realistic detail pass.
 *
 * Two models are involved:
 *  * **GFPGAN v1.4** (`gfpgan_1.4.onnx`, 512x512, optional `weight` input 0..1) restores pores, lashes
 *    and skin micro texture. The `weight` input is the "fidelity" knob: 0 lets the network invent a
 *    plastic face, 1 keeps the identity and the moles. We map the UI slider (0..100) straight onto it,
 *    so a high slider value means *more faithful*, never "more beautiful".
 *  * **Real-ESRGAN x2 fp16** (`real_esrgan_x2_fp16.onnx`) upscales the finished frame 2x with a tiled
 *    forward pass. Used for the 1024 / 4K export path and for grain matching.
 *
 * Everything here is deliberately *optional*: if the user has not downloaded the models the pipeline
 * simply skips the pass and keeps the swapper output.
 */
@Singleton
class HyperRealEnhancer @Inject constructor(
    private val models: ModelManager,
) {
    @Volatile
    private var enhancerSession: OrtSession? = null
    @Volatile
    private var enhancerSpec: SessionSpec? = null
    @Volatile
    private var esrganSession: OrtSession? = null
    @Volatile
    private var esrganSpec: SessionSpec? = null

    /** True when the GFPGAN weights are available. */
    fun isAvailable(): Boolean = models.enhancerFile().exists()

    /** True when Real-ESRGAN is available. */
    fun isUpscalerAvailable(): Boolean = models.frameEnhancerFile().exists()

    /**
     * Runs GFPGAN on a 512x512 FFHQ-aligned crop.
     *
     * @param crop the swapped face crop in RGB (`CV_8UC3`) — any size, it is aligned to [size]
     * @param affine the alignment matrix that maps the *frame* to the 512 crop; used to paste the
     *   refined result back into [frame] when one is supplied to [enhanceFrame]
     * @param size the model resolution (512)
     * @param fidelity 0..1; 1 = keep the swapped identity, 0 = let GFPGAN hallucinate detail
     * @return the refined crop (owned) or null when the model is unavailable
     */
    fun enhanceCrop(crop: Mat, affine: Mat, size: Int, fidelity: Float = 0.65f): Mat? {
        if (!isAvailable()) return null
        return try {
            val session = ensureEnhancer()
            val resized = if (crop.cols() != size || crop.rows() != size) {
                MatUtils.resize(crop, size, size, Imgproc.INTER_LINEAR)
            } else crop.clone()

            // GFPGAN wants (x/255 - 0.5) / 0.5 = x/127.5 - 1 in RGB, NCHW.
            val input = MatUtils.toNchwFloat(
                resized,
                mean = floatArrayOf(0.5f, 0.5f, 0.5f),
                std = floatArrayOf(0.5f, 0.5f, 0.5f),
                scale = 1f / 255f,
                bgr = true,
            )
            resized.release()
            val tensor = OnnxTensor.createTensor(
                OrtEngine.env(), FloatBuffer.wrap(input), longArrayOf(1, 3, size.toLong(), size.toLong()),
            )
            val inputs = mutableMapOf<String, OnnxTensor>()
            inputs[session.inputNames.first()] = tensor
            // The optional fidelity input; the name differs between exports ("weight", "w").
            val weightName = session.inputNames.firstOrNull { it != session.inputNames.first() }
            var weightTensor: OnnxTensor? = null
            if (weightName != null) {
                weightTensor = OnnxTensor.createTensor(
                    OrtEngine.env(), FloatBuffer.wrap(floatArrayOf(fidelity.coerceIn(0f, 1f))), longArrayOf(1),
                )
                inputs[weightName] = weightTensor
            }

            val output = session.run(inputs).use { result ->
                val value = result.get(0)
                check(value is OnnxTensor) { "GFPGAN output is not a tensor" }
                OrtEngine.readFloats(value)
            }
            tensor.close()
            weightTensor?.close()

            // Output is in [-1, 1]; map back to 0..255 RGB.
            for (i in output.indices) output[i] = output[i].coerceIn(-1f, 1f) * 0.5f + 0.5f
            MatUtils.nchwFloatToRgb(output, size, size, bgr = true)
        } catch (t: Throwable) {
            Log.w(TAG, "GFPGAN pass failed, keeping the unenhanced crop", t)
            null
        }
    }

    /**
     * Enhances the whole face region inside [frame] in place.
     *
     * The refined crop is pasted back with the same feathered mask the swapper used, so the enhancement
     * can never introduce a rectangular edge.
     *
     * @param frame RGB `CV_8UC3`, modified in place
     * @param affine frame -> 512 FFHQ alignment
     * @param fidelity strength from the Hyper Realistic slider (0..1)
     * @param regionMask optional frame-space mask (occlusion); if present the enhancement is limited
     *   to it, so glasses and hair stay untouched
     */
    fun enhanceFrame(frame: Mat, affine: Mat, fidelity: Float, regionMask: Mat? = null): Boolean {
        if (!isAvailable()) return false
        val warped = try {
            FaceWarp.warp(frame, affine, 512)
        } catch (t: Throwable) {
            return false
        }
        val refined = enhanceCrop(warped, affine, 512, fidelity)
        warped.release()
        if (refined == null) return false
        try {
            val boxMask = FaceWarp.boxMask(512, border = 0.07)
            val mask = if (regionMask != null) {
                val cropMask = Mat()
                Imgproc.warpAffine(regionMask, cropMask, affine, Size(512.0, 512.0), Imgproc.INTER_LINEAR)
                val normalised = Mat()
                when (cropMask.type()) {
                    CvType.CV_8UC1 -> cropMask.convertTo(normalised, CvType.CV_32FC1, 1.0 / 255.0)
                    else -> cropMask.convertTo(normalised, CvType.CV_32FC1)
                }
                cropMask.release()
                val combined = Mat()
                org.opencv.core.Core.multiply(boxMask, normalised, combined)
                boxMask.release()
                normalised.release()
                combined
            } else {
                boxMask
            }
            FaceWarp.pasteBack(frame, refined, mask, affine)
            mask.release()
            return true
        } finally {
            refined.release()
        }
    }

    /**
     * Tiled 2x super resolution of a whole frame.
     *
     * Real-ESRGAN runs on 256x256 tiles with a 16 px context border, exactly like the reference
     * implementation; a naive full-frame pass would need tens of gigabytes on a 4K frame.
     *
     * @return the upscaled frame (owned) or null when the model is missing
     */
    fun upscale2x(frame: Mat, param: SessionSpec): Mat? {
        if (!isUpscalerAvailable()) return null
        return try {
            val session = ensureEsrgan(param)
            val tile = 256
            val context = 16
            val step = tile - context * 2
            val width = frame.cols()
            val height = frame.rows()
            val outWidth = width * 2
            val outHeight = height * 2
            val output = Mat(outHeight, outWidth, CvType.CV_8UC3)

            var y = 0
            while (y < height) {
                var x = 0
                while (x < width) {
                    val left = (x - context).coerceAtLeast(0)
                    val top = (y - context).coerceAtLeast(0)
                    val right = (x + step + context).coerceAtMost(width)
                    val bottom = (y + step + context).coerceAtMost(height)
                    val patchW = right - left
                    val patchH = bottom - top
                    val patch = Mat(frame, org.opencv.core.Rect(left, top, patchW, patchH)).clone()
                    val padded = Mat.zeros(tile, tile, CvType.CV_8UC3)
                    patch.copyTo(padded.submat(0, patchH, 0, patchW))
                    patch.release()

                    val input = MatUtils.toNchwFloat(padded, scale = 1f / 255f, bgr = true)
                    padded.release()
                    val tensor = OnnxTensor.createTensor(
                        OrtEngine.env(), FloatBuffer.wrap(input), longArrayOf(1, 3, tile.toLong(), tile.toLong()),
                    )
                    val floats = session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                        val value = result.get(0)
                        check(value is OnnxTensor)
                        OrtEngine.readFloats(value)
                    }
                    tensor.close()
                    // ESRGAN output is 0..1 already.
                    val upscaled = MatUtils.nchwFloatToRgb(floats, tile * 2, tile * 2, bgr = true)

                    // Crop the valid area (without the context border) and copy at 2x scale.
                    val validW = min(patchW, width - x)
                    val validH = min(patchH, height - y)
                    val srcRect = org.opencv.core.Rect(context * 2, context * 2, validW * 2, validH * 2)
                    val dstX = x * 2
                    val dstY = y * 2
                    if (dstX + validW * 2 <= outWidth && dstY + validH * 2 <= outHeight) {
                        val roi = Mat(upscaled, srcRect)
                        roi.copyTo(Mat(output, org.opencv.core.Rect(dstX, dstY, validW * 2, validH * 2)))
                    }
                    upscaled.release()
                    x += step
                }
                y += step
            }
            output
        } catch (t: Throwable) {
            Log.w(TAG, "Real-ESRGAN upscale failed", t)
            null
        }
    }

    @Synchronized
    private fun ensureEnhancer(): OrtSession {
        val current = enhancerSession
        if (current != null) return current
        val created = models.sessionFor(models.enhancerFile(), SessionSpec(threads = 4))
        enhancerSession = created
        return created
    }

    @Synchronized
    private fun ensureEsrgan(spec: SessionSpec): OrtSession {
        val current = esrganSession
        if (current != null && esrganSpec == spec) return current
        val created = models.sessionFor(models.frameEnhancerFile(), spec)
        esrganSession = created
        esrganSpec = spec
        return created
    }

    /** Releases the cached sessions (called from the settings screen / on low memory). */
    @Synchronized
    fun release() {
        enhancerSession = null
        esrganSession = null
        esrganSpec = null
    }

    companion object {
        private const val TAG = "HyperRealEnhancer"

        /** Slider value 0..100 -> GFPGAN fidelity weight 0..1. */
        fun sliderToFidelity(slider: Int): Float = (slider.coerceIn(0, 100) / 100f) * 0.85f + 0.10f
    }
}
