package com.swapstudio.ai.core.swap

import android.util.Log
import com.swapstudio.ai.core.geometry.FaceWarp
import com.swapstudio.ai.core.inference.OrtEngine
import com.swapstudio.ai.core.inference.SessionSpec
import com.swapstudio.ai.core.models.ModelManager
import com.swapstudio.ai.core.processing.ColorMatcher
import com.swapstudio.ai.core.util.MatUtils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Normalised face crop used for parsing and for the final, seam free paste back.
 *
 * @param image RGB `CV_8UC3` crop (owned)
 * @param affine the transform that produced it (frame -> crop)
 */
data class FaceCrop(val image: Mat, val affine: Mat, val size: Int)

/**
 * The whole "one face in one frame" pipeline, shared by the photo and the video path.
 *
 * Photo and video must produce **identical** results, otherwise the first video frame would look
 * different from the still export of the same moment. That is why both `PhotoSwapPipeline` and
 * `VideoProcessor` call into this class and only differ in how they feed frames in.
 *
 * Per-frame steps:
 *  1. [prepare]          – align the target face into a 128/256 crop and run the swapper.
 *  2. [parseOcclusion]   – BiSeNet 19 class parse, keeps hair/glasses/hands on top.
 *  3. [colourMatch]      – [ColorMatcher] on the crop (LAB + regional histogram + Poisson).
 *  4. [compose]          – feathered alpha paste with the occlusion mask.
 *  5. [addGrain]         – film grain copied from the target so the face matches the background.
 *  6. [eyeRefine]        – sharpens only the iris/pupil area so eyes never turn to mush.
 */
@Singleton
class FaceBlender @Inject constructor(
    private val models: ModelManager,
    val colors: ColorMatcher,
) {
    private var parserSession: ai.onnxruntime.OrtSession? = null

    // ---------------------------------------------------------------------------------------------
    // 1. Alignment + swap
    // ---------------------------------------------------------------------------------------------

    /** Aligns the target face into a [cropSize] square using the ArcFace 128 template. */
    fun cropFrame(frame: Mat, landmarks: MatOfPoint2f, cropSize: Int = 256): FaceCrop {
        val affine = if (cropSize == 128) {
            FaceWarp.estimateAffine(landmarks, FaceWarp.ARCFACE_128_V2, 128)
        } else {
            // Same template, scaled up: keeps the swap alignment identical while giving the parser and
            // the enhancer more pixels to work with.
            FaceWarp.estimateAffine(landmarks, FaceWarp.ARCFACE_128_V2, cropSize)
        }
        val image = FaceWarp.warp(frame, affine, cropSize)
        return FaceCrop(image, affine, cropSize)
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Face parser (BiSeNet) – occlusion & skin regions
    // ---------------------------------------------------------------------------------------------

    /** Parsed face: per class probability masks at 512x512, plus the usable occlusion/skin masks. */
    data class ParseResult(
        /** 1 = skin class only (0/1 float) */
        val skinMask: Mat,
        /** 1 = wearables / hair / hands (0..1 float, already blurred) */
        val occlusionMask: Mat,
        /** Raw 19 class argmax map (`CV_8UC1`, values 0..18) for debugging */
        val classMap: Mat,
    )

    /** CelebAMask-HQ class ids returned by the BiSeNet export. */
    object ParseClass {
        const val BACKGROUND = 0
        const val SKIN = 1
        const val NOSE = 2
        const val EYE_GLASSES = 3
        const val LEFT_EYE = 4
        const val RIGHT_EYE = 5
        const val LEFT_BROW = 6
        const val RIGHT_BROW = 7
        const val LEFT_EAR = 8
        const val RIGHT_EAR = 9
        const val MOUTH = 10
        const val LOWER_LIP = 11
        const val UPPER_LIP = 12
        const val HAIR = 13
        const val HAT = 14
        const val EARRING = 15
        const val NECKLACE = 16
        const val NECK = 17
        const val CLOTH = 18

        /** Everything the swap must never cover. */
        val OCCLUDING = intArrayOf(EYE_GLASSES, HAIR, HAT, EARRING, NECKLACE, CLOTH, LEFT_EAR, RIGHT_EAR)

        /** Classes that count as "face interior" for the colour transfer. */
        val FACE = intArrayOf(SKIN, NOSE, LEFT_EYE, RIGHT_EYE, LEFT_BROW, RIGHT_BROW, MOUTH, LOWER_LIP, UPPER_LIP)
    }

    /**
     * Runs the BiSeNet parser on a face crop.
     *
     * Input contract of `bisenet_resnet_34.onnx` (FaceFusion "face parser"): 512x512, **BGR**, scaled to
     * 0..1, normalised with ImageNet mean/std, NCHW. Output: `[1, 19, 512, 512]` logits.
     *
     * @return [ParseResult] with masks at the crop resolution, or null when the model is missing
     */
    fun parseOcclusion(crop: Mat, spec: SessionSpec = SessionSpec(threads = 4)): ParseResult? {
        if (!models.parserFile().exists()) return null
        return try {
            val session = ensureParser(spec)
            val rgb = if (crop.cols() != 512 || crop.rows() != 512) MatUtils.resize(crop, 512, 512) else crop
            val logits = runParser(session, rgb)
            if (rgb !== crop) rgb.release()
            val classes = argmax(logits, 19, 512)
            val classMap = Mat(512, 512, CvType.CV_8UC1)
            classMap.put(0, 0, classes)

            val skin = maskOfClasses(classes, intArrayOf(ParseClass.SKIN, ParseClass.NOSE))
            val occlusion = maskOfClasses(classes, ParseClass.OCCLUDING)
            val skinFloat = Mat()
            skin.convertTo(skinFloat, CvType.CV_32FC1, 1.0 / 255.0)
            skin.release()
            val occlusionFloat = Mat()
            occlusion.convertTo(occlusionFloat, CvType.CV_32FC1, 1.0 / 255.0)
            occlusion.release()
            val blurred = Mat()
            Imgproc.GaussianBlur(occlusionFloat, blurred, Size(0.0, 0.0), 5.0)
            // Same remap FaceFusion uses: values above 0.5 are "fully occluded", the rest fades out.
            val remapped = Mat()
            org.opencv.core.Core.multiply(blurred, Scalar.all(2.0), blurred)
            org.opencv.core.Core.subtract(blurred, Scalar.all(1.0), blurred)
            org.opencv.core.Core.max(blurred, Mat.zeros(blurred.size(), CvType.CV_32FC1), remapped)
            occlusionFloat.release()
            blurred.release()
            ParseResult(skinFloat, remapped, classMap)
        } catch (t: Throwable) {
            Log.w(TAG, "face parsing failed, continuing without occlusion", t)
            null
        }
    }

    private fun runParser(session: ai.onnxruntime.OrtSession, rgb512: Mat): FloatArray {
        val input = MatUtils.toNchwFloat(
            rgb512,
            mean = floatArrayOf(0.485f, 0.456f, 0.406f),
            std = floatArrayOf(1f / 0.229f, 1f / 0.224f, 1f / 0.225f),
            scale = 1f / 255f,
            bgr = true,
        )
        val tensor = ai.onnxruntime.OnnxTensor.createTensor(
            OrtEngine.env(), java.nio.FloatBuffer.wrap(input), longArrayOf(1, 3, 512, 512),
        )
        val out = session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
            val value = result.get(0)
            check(value is ai.onnxruntime.OnnxTensor) { "parser output is not a tensor" }
            OrtEngine.readFloats(value)
        }
        tensor.close()
        return out
    }

    /** Argmax over [classCount] channels of an NCHW float array. */
    private fun argmax(logits: FloatArray, classCount: Int, size: Int): ByteArray {
        val plane = size * size
        val out = ByteArray(plane)
        for (i in 0 until plane) {
            var best = 0
            var bestValue = logits[i]
            for (c in 1 until classCount) {
                val v = logits[c * plane + i]
                if (v > bestValue) {
                    bestValue = v
                    best = c
                }
            }
            out[i] = best.toByte()
        }
        return out
    }

    /** Builds a 0/255 mask of the requested classes. */
    private fun maskOfClasses(classes: ByteArray, wanted: IntArray): Mat {
        val bytes = ByteArray(classes.size)
        for (i in classes.indices) {
            val c = classes[i].toInt()
            if (c in wanted) bytes[i] = 255.toByte()
        }
        return MatUtils.maskFromBytes(bytes, 512, 512)
    }

    @Synchronized
    private fun ensureParser(spec: SessionSpec): ai.onnxruntime.OrtSession {
        parserSession?.let { return it }
        val created = models.sessionFor(models.parserFile(), spec)
        parserSession = created
        return created
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Colour
    // ---------------------------------------------------------------------------------------------

    /**
     * Colour matches the swapped crop against the target crop.
     *
     * Runs on the crop, in the FOMM/ArcFace alignment space, so the region polygons are the same for
     * every frame and a video cannot "jump" between two alignment conventions.
     *
     * @param swapped RGB crop (modified copy returned)
     * @param targetFrame the full target frame (source of the skin tone)
     * @param targetCrop the original (unswapped) crop, used as the geometry reference for regions
     */
    fun colourMatch(
        swapped: Mat,
        targetFrame: Mat,
        targetCrop: Mat,
        parse: ParseResult?,
        landmarks: Array<Point>,
        faceId: Int,
        options: ColorMatcher.BlendOptions = ColorMatcher.BlendOptions(),
    ): Mat {
        // Region polygons are computed on the *frame* mesh; here we work in crop space, so map the
        // mesh into the crop first (the caller passes crop space landmarks).
        val mask = colors.createFeatheredSkinMask(landmarks, swapped.cols(), swapped.rows())
        try {
            val parserSkin = parse?.skinMask?.let { skin ->
                if (skin.cols() != swapped.cols() || skin.rows() != swapped.rows()) {
                    MatUtils.resize(skin, swapped.cols(), swapped.rows(), Imgproc.INTER_NEAREST)
                } else skin.clone()
            }
            if (parserSkin != null) {
                val binary = MatUtils.normaliseMask(parserSkin)
                parserSkin.release()
                org.opencv.core.Core.bitwise_and(mask, binary, mask)
                binary.release()
            }

            // 1:1 because the crop and the target crop share the same coordinate space.
            val reference = if (targetCrop.cols() == swapped.cols() && targetCrop.rows() == swapped.rows()) {
                targetCrop
            } else {
                MatUtils.resize(targetCrop, swapped.cols(), swapped.rows())
            }
            val normalised = colors.normalise(swapped, mask = mask)
            val sourceMoments = colors.measureLab(normalised, mask)
            val targetMoments = if (options.useTemporal) {
                colors.temporalMean(faceId, colors.measureLab(reference, mask), options.temporalAlpha)
            } else {
                colors.measureLab(reference, mask)
            }
            var current = colors.ethnicityAdaptiveLabTransfer(
                source = normalised,
                target = reference,
                mask = mask,
                sourceMoments = sourceMoments,
                targetMoments = targetMoments,
                texturePreservation = options.texturePreservation,
            )
            normalised.release()
            if (options.useRegionalHistogram) {
                val regional = colors.regionalHistogramMatch(current, reference, landmarks, blend = options.regionalBlend)
                current.release()
                current = regional
            }
            if (reference !== targetCrop) reference.release()
            return current
        } finally {
            mask.release()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Compose back into the frame
    // ---------------------------------------------------------------------------------------------

    /**
     * Pastes [crop] back into [frame] (in place) with an edge feathered mask.
     *
     * @param faceMask optional crop space skin mask (`CV_8UC1`) that limits where the paste happens
     * @param occlusion optional crop space occlusion mask (`CV_32FC1`, 0..1) from the parser
     * @param feather edge feather in pixels
     */
    fun compose(
        frame: Mat,
        crop: Mat,
        affine: Mat,
        faceMask: Mat? = null,
        occlusion: Mat? = null,
        feather: Int = 12,
        strength: Float = 1f,
    ) {
        val cropMask = Mat.ones(crop.rows(), crop.cols(), CvType.CV_32FC1)
        // Feather the crop border: a soft rectangle, never a hard edge.
        val border = (min(crop.cols(), crop.rows()) * 0.06).roundToInt().coerceAtLeast(2)
        val innerRect = org.opencv.core.Rect(border, border, crop.cols() - border * 2, crop.rows() - border * 2)
        val inner = Mat.zeros(cropMask.size(), CvType.CV_32FC1)
        Mat.ones(innerRect.height, innerRect.width, CvType.CV_32FC1).copyTo(inner.submat(innerRect))
        Imgproc.GaussianBlur(inner, cropMask, Size(0.0, 0.0), border / 1.5)
        inner.release()

        if (faceMask != null) {
            val skin = Mat()
            faceMask.convertTo(skin, CvType.CV_32FC1, 1.0 / 255.0)
            org.opencv.core.Core.multiply(cropMask, skin, cropMask)
            skin.release()
        }
        if (occlusion != null) {
            val free = Mat(occlusion.size(), CvType.CV_32FC1, Scalar.all(1.0))
            org.opencv.core.Core.subtract(free, occlusion, free)
            org.opencv.core.Core.max(free, Mat.zeros(free.size(), CvType.CV_32FC1), free)
            org.opencv.core.Core.multiply(cropMask, free, cropMask)
            free.release()
        }
        if (strength < 1f) {
            org.opencv.core.Core.multiply(cropMask, Scalar.all(strength.toDouble()), cropMask)
        }
        if (feather > 0) {
            val soft = Mat()
            Imgproc.GaussianBlur(cropMask, soft, Size(0.0, 0.0), feather / 3.0)
            soft.copyTo(cropMask)
            soft.release()
        }
        FaceWarp.pasteBack(frame, crop, cropMask, affine)
        cropMask.release()
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Grain
    // ---------------------------------------------------------------------------------------------

    /**
     * Estimates the film/sensor grain of the target and adds a matching amount to the pasted face.
     *
     * Method: high-pass the target with a 3x3 Gaussian, measure the robust standard deviation of the
     * residual on the four bands around the face (never on the face itself, so the swapper output does
     * not bias the estimate), then add zero-mean noise of the same sigma inside the face mask only.
     *
     * @param amount extra multiplier (0 = off, 1 = match exactly)
     */
    fun addGrain(
        frame: Mat,
        faceBounds: DoubleArray,
        mask: Mat?,
        amount: Float = 1f,
        seed: Long = 0L,
    ): Unit {
        val sigma = estimateGrainSigma(frame, faceBounds)
        if (sigma <= 0.4f || amount <= 0f) return
        val region = mask ?: run {
            // No skin mask supplied: restrict the grain to the face oval.
            val rect = clampRect(faceBounds, frame.cols(), frame.rows())
            val oval = Mat.zeros(frame.rows(), frame.cols(), CvType.CV_8UC1)
            Imgproc.ellipse(
                oval,
                Point(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0),
                Size(rect.width * 0.42, rect.height * 0.46),
                0.0, 0.0, 360.0, Scalar.all(255.0), -1,
            )
            oval
        }
        val float = Mat()
        frame.convertTo(float, CvType.CV_32FC3)
        val noise = Mat(float.size(), CvType.CV_32FC3)
        val random = java.util.Random(seed)
        val sigmaValue = sigma * amount * 0.55f
        // Box-Muller in a tight loop is faster than repeated Random.nextGaussian() calls.
        val bytes = FloatArray(float.rows() * float.cols() * 3)
        for (i in bytes.indices) {
            val u1 = random.nextDouble().coerceAtLeast(1e-7)
            val u2 = random.nextDouble()
            val gauss = sqrt(-2.0 * Math.log(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
            bytes[i] = (gauss * sigmaValue).toFloat()
        }
        noise.put(0, 0, bytes)

        // Normalise the mask to 0..1 float, three channels so it multiplies the noise.
        val normalised = Mat()
        if (region.type() == CvType.CV_32FC1 && org.opencv.core.Core.minMaxLoc(region).maxVal <= 1.001) {
            region.copyTo(normalised)
        } else {
            region.convertTo(normalised, CvType.CV_32FC1, 1.0 / 255.0)
        }
        val maskFloat = Mat()
        Imgproc.cvtColor(normalised, maskFloat, Imgproc.COLOR_GRAY2RGB)
        normalised.release()
        val maskedNoise = Mat()
        org.opencv.core.Core.multiply(noise, maskFloat, maskedNoise)
        val noisy = Mat()
        org.opencv.core.Core.add(float, maskedNoise, noisy)
        noisy.convertTo(frame, CvType.CV_8UC3)
        listOf(float, noise, maskFloat, maskedNoise, noisy).forEach { it.release() }
    }

    /** Robust grain sigma of the region surrounding the face. */
    fun estimateGrainSigma(frame: Mat, faceBounds: DoubleArray): Float {
        val gray = Mat()
        Imgproc.cvtColor(frame, gray, Imgproc.COLOR_RGB2GRAY)
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.0)
        val residual = Mat()
        org.opencv.core.Core.subtract(gray, blurred, residual, Mat(), CvType.CV_32F)
        blurred.release()
        gray.release()

        val x = faceBounds[0].toInt()
        val y = faceBounds[1].toInt()
        val w = faceBounds[2].toInt()
        val h = faceBounds[3].toInt()
        val band = (w * 0.35).toInt().coerceAtLeast(8)
        val samples = ArrayList<Double>()
        // Left and right bands next to the face, plus a band above the forehead.
        samples += bandValues(residual, x - band * 2, y, band, h)
        samples += bandValues(residual, x + w + band, y, band, h)
        samples += bandValues(residual, x, y - band * 2, w, band)
        residual.release()
        if (samples.isEmpty()) return 0f
        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2]
        val deviations = samples.map { abs(it - median) }
        val mad = deviations.sorted()[deviations.size / 2]
        return (mad * 1.4826).toFloat()
    }

    private fun bandValues(residual: Mat, x: Int, y: Int, w: Int, h: Int): List<Double> {
        val rect = clampRect(doubleArrayOf(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble()), residual.cols(), residual.rows())
        if (rect.width < 2 || rect.height < 2) return emptyList()
        val roi = Mat(residual, rect)
        val values = DoubleArray(roi.cols() * roi.rows() * roi.channels())
        return try {
            roi.get(0, 0, values)
            values.toList()
        } catch (t: Throwable) {
            // Fallback for exotic types: sample a grid.
            val list = ArrayList<Double>()
            for (yy in 0 until roi.rows() step 2) for (xx in 0 until roi.cols() step 2) {
                list += roi.get(yy, xx)?.firstOrNull() ?: 0.0
            }
            list
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Eyes
    // ---------------------------------------------------------------------------------------------

    /**
     * Sharpens the eye region only.
     *
     * Face restoration models tend to blur pupils because they are trained on average faces. A small
     * unsharp mask (sigma 1.2, amount 0.35) restricted to the two eye ellipses brings the pupil edge
     * back without touching the skin. This runs *after* everything else so nothing can smear it again.
     */
    fun eyeRefine(frame: Mat, landmarks: Array<Point>, amount: Float = 0.35f) {
        if (landmarks.size < com.swapstudio.ai.core.geometry.FaceGeometry.MESH_POINT_COUNT) return
        val eyeMask = Mat.zeros(frame.rows(), frame.cols(), CvType.CV_8UC1)
        val right = com.swapstudio.ai.core.geometry.FaceGeometry.eyeMask(
            landmarks, com.swapstudio.ai.core.geometry.FaceGeometry.RIGHT_EYE_RING, frame.cols(), frame.rows(), 1.25,
        )
        val left = com.swapstudio.ai.core.geometry.FaceGeometry.eyeMask(
            landmarks, com.swapstudio.ai.core.geometry.FaceGeometry.LEFT_EYE_RING, frame.cols(), frame.rows(), 1.25,
        )
        org.opencv.core.Core.bitwise_or(right, left, eyeMask)
        right.release()
        left.release()
        val soft = Mat()
        Imgproc.GaussianBlur(eyeMask, soft, Size(0.0, 0.0), 2.0)

        val blurred = Mat()
        Imgproc.GaussianBlur(frame, blurred, Size(0.0, 0.0), 1.2)
        val sharpened = Mat()
        org.opencv.core.Core.addWeighted(frame, 1.0 + amount.toDouble(), blurred, -amount.toDouble(), 0.0, sharpened)
        blurred.release()

        val mask3 = Mat()
        Imgproc.cvtColor(soft, mask3, Imgproc.COLOR_GRAY2RGB)
        val maskFloat = Mat()
        mask3.convertTo(maskFloat, CvType.CV_32FC3, 1.0 / 255.0)
        val frameFloat = Mat()
        frame.convertTo(frameFloat, CvType.CV_32FC3)
        val sharpFloat = Mat()
        sharpened.convertTo(sharpFloat, CvType.CV_32FC3)
        val inv = Mat(maskFloat.size(), CvType.CV_32FC3, Scalar.all(1.0))
        org.opencv.core.Core.subtract(inv, maskFloat, inv)
        val a = Mat()
        val b = Mat()
        org.opencv.core.Core.multiply(sharpFloat, maskFloat, a)
        org.opencv.core.Core.multiply(frameFloat, inv, b)
        val sum = Mat()
        org.opencv.core.Core.add(a, b, sum)
        sum.convertTo(frame, CvType.CV_8UC3)
        listOf(eyeMask, soft, sharpened, mask3, maskFloat, frameFloat, sharpFloat, inv, a, b, sum)
            .forEach { it.release() }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun clampRect(bounds: DoubleArray, width: Int, height: Int): org.opencv.core.Rect {
        val left = bounds[0].toInt().coerceIn(0, max(0, width - 1))
        val top = bounds[1].toInt().coerceIn(0, max(0, height - 1))
        val right = (bounds[0] + bounds[2]).toInt().coerceIn(left + 1, width)
        val bottom = (bounds[1] + bounds[3]).toInt().coerceIn(top + 1, height)
        return org.opencv.core.Rect(left, top, right - left, bottom - top)
    }

    /** Gaussian falloff, used for the mesh debug overlay and for the transition bands. */
    fun gaussian(x: Float, sigma: Float): Float = exp(-(x * x) / (2f * sigma * sigma)).toFloat()

    /** Releases the cached parser session. */
    @Synchronized
    fun release() {
        parserSession = null
    }

    private companion object {
        const val TAG = "FaceBlender"
    }
}
