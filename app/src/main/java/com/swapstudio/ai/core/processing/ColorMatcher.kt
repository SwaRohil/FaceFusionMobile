package com.swapstudio.ai.core.processing

import com.swapstudio.ai.core.geometry.FaceGeometry
import com.swapstudio.ai.core.util.MatUtils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The colour engine of SwapStudio.
 *
 * The whole point of this class is the "no ashing, no orange, no plastic" requirement:
 *
 *  1. [createFeatheredSkinMask]  – a skin-only mask from the 478 point mesh, minus eyes, brows, lips,
 *     nostrils, glasses, hair and beard, blurred (21 px) and eroded (5 px).
 *  2. [normalise]                – LAB + CLAHE (clip limit 2.0) on L only + Gray-World white balance,
 *     so exposure and colour cast differences between source and target stop mattering.
 *  3. [ethnicityAdaptiveLabTransfer] – LAB mean/std transfer where **L is matched at 100 %** but A/B
 *     only partially (0.30 for Fitzpatrick V-VI, 0.45 for III-IV, 0.60 for I-II). That is what stops a
 *     pale face from turning grey on a dark one and stops a dark face from turning orange on a pale one.
 *  4. [regionalHistogramMatch]   – independent L histogram matching for forehead, both cheeks and the
 *     chin/nose column, which kills the "one side of the face is in shadow" artefact.
 *  5. [poissonBlend]             – `Photo.seamlessClone` with MIXED_CLONE when the lighting difference is
 *     large (dramatic night/harsh light) and NORMAL_CLONE otherwise, so the seam disappears without
 *     washing out the skin.
 *  6. Temporal EMA               – [temporalMean] / [applyTemporal], 0.85 previous + 0.15 new, so video
 *     does not flicker.
 *
 * Colour-space note: OpenCV stores 8-bit LAB with **L in 0..255** and a/b offset by 128. All user facing
 * numbers (Fitzpatrick thresholds 45 / 65) are expressed in the conventional **L\* 0..100** range, so
 * [LabMoments.lightness] converts and [LabMoments.fromLab255] is the only place that knows about both.
 *
 * Every public function is safe to call from `Dispatchers.Default` and releases the temporary mats it
 * allocates; returned mats are owned by the caller.
 */
class ColorMatcher {

    /** Per-face temporal state, keyed by an externally supplied face id (0..n). */
    private val temporal = ConcurrentHashMap<Int, LabMoments>()

    // =============================================================================================
    // 1. Mask
    // =============================================================================================

    /**
     * Builds the skin-only mask.
     *
     * @param landmarks 478 mesh points in pixels, or fewer (the routine degrades gracefully)
     * @param width frame width
     * @param height frame height
     * @param glassesRegions optional extra polygons (e.g. from the face parser) that must be excluded
     * @param beardRegions optional polygons for a beard/moustache
     */
    fun createFeatheredSkinMask(
        landmarks: Array<Point>,
        width: Int,
        height: Int,
        glassesRegions: List<List<Point>> = emptyList(),
        beardRegions: List<List<Point>> = emptyList(),
        erosionPx: Int = 5,
        blurPx: Int = 21,
    ): Mat {
        if (landmarks.size < FaceGeometry.MESH_POINT_COUNT) {
            // Not a full mesh: fall back to an ellipse over whatever we got.
            val mask = Mat.zeros(height, width, CvType.CV_8UC1)
            val bounds = FaceGeometry.boundsFromMesh(landmarks)
            if (bounds[2] > 0 && bounds[3] > 0) {
                Imgproc.ellipse(
                    mask,
                    Point(bounds[0] + bounds[2] / 2, bounds[1] + bounds[3] * 0.55),
                    Size(bounds[2] * 0.36, bounds[3] * 0.32),
                    0.0, 0.0, 360.0, Scalar(255.0), -1,
                )
            }
            return mask
        }

        // (a) silhouette
        val mask = FaceGeometry.regionMask(landmarks, FaceGeometry.FACE_OVAL, width, height)

        // (b) cut out everything that is not skin
        val exclusions = ArrayList<Mat>()
        exclusions += FaceGeometry.regionMask(landmarks, FaceGeometry.RIGHT_EYE_RING, width, height)
        exclusions += FaceGeometry.regionMask(landmarks, FaceGeometry.LEFT_EYE_RING, width, height)
        exclusions += FaceGeometry.regionMask(landmarks, FaceGeometry.RIGHT_EYEBROW, width, height)
        exclusions += FaceGeometry.regionMask(landmarks, FaceGeometry.LEFT_EYEBROW, width, height)
        exclusions += FaceGeometry.regionMask(landmarks, FaceGeometry.LIPS_OUTER, width, height)
        exclusions += FaceGeometry.regionMask(landmarks, FaceGeometry.LIPS_INNER, width, height)
        exclusions += FaceGeometry.regionMask(landmarks, FaceGeometry.NOSTRILS, width, height)
        // Irises are small discs; grow the eye rings slightly so the lid line is excluded too.
        exclusions += FaceGeometry.eyeMask(landmarks, FaceGeometry.RIGHT_EYE_RING, width, height, 1.05)
        exclusions += FaceGeometry.eyeMask(landmarks, FaceGeometry.LEFT_EYE_RING, width, height, 1.05)
        glassesRegions.forEach { exclusions += FaceGeometry.polygonMask(it, width, height) }
        beardRegions.forEach { exclusions += FaceGeometry.polygonMask(it, width, height) }

        val union = Mat.zeros(height, width, CvType.CV_8UC1)
        exclusions.forEach { Core.bitwise_or(union, it, union) }
        // Grow the exclusions a little so the transition ring around the lips and eyes is never matched.
        val grown = MatUtils.dilate(union, 2)
        Core.bitwise_not(grown, grown)
        Core.bitwise_and(mask, grown, mask)

        union.release()
        grown.release()
        exclusions.forEach { it.release() }

        // (c) erode 5 px, then Gaussian blur so the mask edge is a gradient (feathering).
        val eroded = MatUtils.erode(mask, erosionPx)
        mask.release()
        val feathered = MatUtils.feather(eroded, blurPx / 2)
        eroded.release()
        return feathered
    }

    /** Convenience overload that derives the mask from a full mesh, optionally intersected with a parser mask. */
    fun createFeatheredSkinMask(
        mesh: com.swapstudio.ai.core.mesh.MeshFace,
        width: Int,
        height: Int,
        parserMask: Mat? = null,
    ): Mat {
        val mask = createFeatheredSkinMask(mesh.landmarks, width, height)
        if (parserMask != null) {
            // Intersect with the parser's skin classes so hair/glasses/hands are excluded exactly.
            val resized = if (parserMask.cols() != width || parserMask.rows() != height) {
                MatUtils.resize(parserMask, width, height, Imgproc.INTER_NEAREST)
            } else parserMask.clone()
            val binary = MatUtils.normaliseMask(resized)
            resized.release()
            Core.bitwise_and(mask, binary, mask)
            binary.release()
        }
        return mask
    }

    // =============================================================================================
    // 2. Normalisation
    // =============================================================================================

    /**
     * Exposure + white balance normalisation, applied to a **copy** of [rgb].
     *
     * * LAB conversion, CLAHE 2.0 on L only (colour channels untouched, so no hue shift).
     * * Gray-World white balance: per channel gain so the mean of the three channels matches, then a
     *   blend towards the original ([grayWorldStrength]) to avoid destroying intentionally warm/cool
     *   lighting.
     *
     * @return owned `CV_8UC3` RGB mat
     */
    fun normalise(rgb: Mat, claheClip: Double = 2.0, grayWorldStrength: Double = 0.5, mask: Mat? = null): Mat {
        // --- CLAHE on L ---------------------------------------------------------------------
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val channels = ArrayList<Mat>(3)
        Core.split(lab, channels)
        val clahe = Imgproc.createCLAHE(claheClip, Size(8.0, 8.0))
        val lEnhanced = Mat()
        clahe.apply(channels[0], lEnhanced)
        val merged = Mat()
        Core.merge(listOf(lEnhanced, channels[1], channels[2]), merged)
        val lCorrected = Mat()
        Imgproc.cvtColor(merged, lCorrected, Imgproc.COLOR_Lab2RGB)
        listOf(lab, lEnhanced, merged).forEach { it.release() }
        channels.forEach { it.release() }
        clahe.collectGarbage()

        // --- Gray-World white balance ---------------------------------------------------------
        val means = DoubleArray(3)
        for (c in 0 until 3) {
            val channel = Mat()
            Core.extractChannel(lCorrected, channel, c)
            means[c] = Core.mean(channel, mask).`val`[0].coerceAtLeast(1.0)
            channel.release()
        }
        val gray = (means[0] + means[1] + means[2]) / 3.0
        val floatImage = Mat()
        lCorrected.convertTo(floatImage, CvType.CV_32FC3)
        lCorrected.release()
        val floatChannels = ArrayList<Mat>(3)
        Core.split(floatImage, floatChannels)
        for (c in 0 until 3) {
            val gain = (1.0 + (gray / means[c] - 1.0) * grayWorldStrength).coerceIn(0.75, 1.33)
            Core.multiply(floatChannels[c], Scalar.all(gain), floatChannels[c])
        }
        Core.merge(floatChannels, floatImage)
        floatChannels.forEach { it.release() }
        val out = Mat()
        floatImage.convertTo(out, CvType.CV_8UC3)
        floatImage.release()
        return out
    }

    // =============================================================================================
    // 3. Ethnicity-preserving LAB transfer
    // =============================================================================================

    /**
     * Mean / std of the L, A and B channels inside the mask, in OpenCV's 8-bit LAB encoding
     * (L 0..255, A/B 0..255 with 128 = neutral).
     */
    data class LabMoments(val mean: DoubleArray, val std: DoubleArray) {

        /** Perceived lightness in the conventional L\* 0..100 range. */
        val lightness: Double get() = mean[0] * 100.0 / 255.0

        /** Fitzpatrick estimate from L\*: V-VI below 45, III-IV below 65, I-II above. */
        val fitzpatrick: String
            get() = when {
                lightness < 45.0 -> "V-VI"
                lightness < 65.0 -> "III-IV"
                else -> "I-II"
            }

        /** Partial A/B transfer strength: 0.30 / 0.45 / 0.60. */
        val chromaStrength: Double
            get() = when {
                lightness < 45.0 -> CHROMA_STRENGTH_DARK
                lightness < 65.0 -> CHROMA_STRENGTH_MEDIUM
                else -> CHROMA_STRENGTH_LIGHT
            }

        /** A/B chroma magnitude relative to neutral (0 = grey). */
        val chromaMagnitude: Double
            get() = sqrt((mean[1] - 128.0) * (mean[1] - 128.0) + (mean[2] - 128.0) * (mean[2] - 128.0))

        /** Blend of the moments, used by the temporal EMA. */
        fun lerp(other: LabMoments, t: Double): LabMoments = LabMoments(
            DoubleArray(3) { mean[it] * (1 - t) + other.mean[it] * t },
            DoubleArray(3) { std[it] * (1 - t) + other.std[it] * t },
        )

        /** Parses `Core.meanStdDev` output. */
        companion object {
            fun of(mean: DoubleArray, std: DoubleArray): LabMoments = LabMoments(
                mean.copyOf(),
                DoubleArray(3) { std.getOrElse(it) { 1.0 }.coerceAtLeast(1.0) },
            )
        }
    }

    /** Measures the LAB statistics of [rgb] inside [mask] (null mask = whole image). */
    fun measureLab(rgb: Mat, mask: Mat?): LabMoments {
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val (mean, std) = MatUtils.maskedMeanStd(lab, mask)
        lab.release()
        return LabMoments.of(mean, std)
    }

    /**
     * Transfers the colour statistics of [target] onto [source] **inside [mask]**.
     *
     * Formulas (per pixel, per channel):
     * ```
     * L' = (L - meanSrc_L) * clamp(stdTgt_L / stdSrc_L, 0.6, 1.6) + meanTgt_L
     * a' = (a - meanSrc_a) * r_a + meanSrc_a + strength * (meanTgt_a - meanSrc_a)
     *      where r_a = 1 + (stdTgt_a / stdSrc_a - 1) * strength
     * ```
     * The chroma channel is thus only *partially* matched, which is the whole anti-ashing / anti-orange
     * trick: the hue of the target wins, but the saturation of the source is only nudged.
     *
     * @param texturePreservation fraction of the source kept inside the mask (0.12 = keep 12 %)
     * @return owned `CV_8UC3` RGB mat; [source] is not modified
     */
    fun ethnicityAdaptiveLabTransfer(
        source: Mat,
        target: Mat,
        mask: Mat,
        sourceMoments: LabMoments? = null,
        targetMoments: LabMoments? = null,
        texturePreservation: Double = 0.12,
    ): Mat {
        val srcMoments = sourceMoments ?: measureLab(source, mask)
        val tgtMoments = targetMoments ?: measureLab(target, mask)
        val strength = tgtMoments.chromaStrength

        val srcLab = Mat()
        Imgproc.cvtColor(source, srcLab, Imgproc.COLOR_RGB2Lab)
        val channels = ArrayList<Mat>(3)
        Core.split(srcLab, channels)

        // ---- L: full transfer ----------------------------------------------------------------
        val lRatio = (tgtMoments.std[0] / srcMoments.std[0]).coerceIn(L_RATIO_MIN, L_RATIO_MAX)
        val lOffset = tgtMoments.mean[0] - srcMoments.mean[0] * lRatio
        val lFloat = Mat()
        channels[0].convertTo(lFloat, CvType.CV_32FC1)
        Core.multiply(lFloat, Scalar.all(lRatio), lFloat)
        Core.add(lFloat, Scalar.all(lOffset), lFloat)
        lFloat.convertTo(channels[0], CvType.CV_8UC1)
        lFloat.release()

        // ---- A / B: partial transfer ----------------------------------------------------------
        for (c in 1..2) {
            val stdRatio = (tgtMoments.std[c] / srcMoments.std[c]).coerceIn(0.7, 1.4)
            val ratio = 1.0 + (stdRatio - 1.0) * strength
            val srcMean = srcMoments.mean[c]
            val delta = (tgtMoments.mean[c] - srcMean) * strength
            val float = Mat()
            channels[c].convertTo(float, CvType.CV_32FC1)
            Core.subtract(float, Scalar.all(srcMean), float)
            Core.multiply(float, Scalar.all(ratio), float)
            Core.add(float, Scalar.all(srcMean + delta), float)
            float.convertTo(channels[c], CvType.CV_8UC1)
            float.release()
        }

        val merged = Mat()
        Core.merge(channels, merged)
        val matched = Mat()
        Imgproc.cvtColor(merged, matched, Imgproc.COLOR_Lab2RGB)
        srcLab.release()
        merged.release()
        channels.forEach { it.release() }

        // Bilateral filter (d9) removes banding on smooth cheeks without destroying pores.
        val smooth = Mat()
        Imgproc.bilateralFilter(matched, smooth, 9, 45.0, 45.0)
        matched.release()

        // ---- Alpha blend: matched inside the mask, source outside, a little source everywhere -----
        val mask3 = Mat()
        Imgproc.cvtColor(mask, mask3, Imgproc.COLOR_GRAY2RGB)
        val maskFloat = Mat()
        mask3.convertTo(maskFloat, CvType.CV_32FC3, (1.0 - texturePreservation) / 255.0)
        mask3.release()
        val inverseMask = Mat(maskFloat.size(), CvType.CV_32FC3, Scalar.all(1.0))
        Core.subtract(inverseMask, maskFloat, inverseMask)
        val smoothFloat = Mat()
        smooth.convertTo(smoothFloat, CvType.CV_32FC3)
        smooth.release()
        val sourceFloat = Mat()
        source.convertTo(sourceFloat, CvType.CV_32FC3)
        val inside = Mat()
        val outside = Mat()
        Core.multiply(smoothFloat, maskFloat, inside)
        Core.multiply(sourceFloat, inverseMask, outside)
        val sum = Mat()
        Core.add(inside, outside, sum)
        val out = Mat()
        sum.convertTo(out, CvType.CV_8UC3)
        listOf(maskFloat, inverseMask, smoothFloat, sourceFloat, inside, outside, sum).forEach { it.release() }
        return out
    }

    // =============================================================================================
    // 4. Regional histogram match
    // =============================================================================================

    /**
     * Matches the L histogram of four regions (forehead, left cheek, right cheek, chin/nose) of
     * [source] to the same regions of [target].
     *
     * This is the fix for "one cheek is in shadow": a global transfer averages the two sides together
     * and ends up too light on the shadow side and too dark on the lit side. Doing it per region keeps
     * the direction of the light.
     *
     * @param landmarks mesh of the face in [source] space (identical to the target space after the swap)
     */
    fun regionalHistogramMatch(
        source: Mat,
        target: Mat,
        landmarks: Array<Point>,
        regions: List<FaceGeometry.Region> = listOf(
            FaceGeometry.Region.FOREHEAD,
            FaceGeometry.Region.LEFT_CHEEK,
            FaceGeometry.Region.RIGHT_CHEEK,
            FaceGeometry.Region.CHIN_NOSE,
        ),
        blend: Double = 0.75,
    ): Mat {
        val width = source.cols()
        val height = source.rows()
        val srcLab = Mat()
        Imgproc.cvtColor(source, srcLab, Imgproc.COLOR_RGB2Lab)
        val tgtLab = Mat()
        Imgproc.cvtColor(target, tgtLab, Imgproc.COLOR_RGB2Lab)
        val srcChannels = ArrayList<Mat>(3)
        Core.split(srcLab, srcChannels)
        val tgtChannels = ArrayList<Mat>(3)
        Core.split(tgtLab, tgtChannels)

        for (region in regions) {
            val polygon = FaceGeometry.regionPolygon(landmarks, region)
            if (polygon.size < 3) continue
            val regionMask = FaceGeometry.polygonMask(polygon, width, height)
            val eroded = MatUtils.erode(regionMask, 3)
            regionMask.release()
            if (Core.countNonZero(eroded) < 64) {
                eroded.release()
                continue
            }
            // Histogram specification of L inside this region only (BOTH histograms restricted to
            // the region: a lit forehead must not become darker just because the chin is in shadow).
            val matchedL = matchHistogram(srcChannels[0], tgtChannels[0], eroded, eroded)
            val maskFloat = Mat()
            eroded.convertTo(maskFloat, CvType.CV_32FC1, blend / 255.0)
            eroded.release()
            val base = Mat()
            srcChannels[0].convertTo(base, CvType.CV_32FC1)
            val matchedFloat = Mat()
            matchedL.convertTo(matchedFloat, CvType.CV_32FC1)
            matchedL.release()
            val inv = Mat(maskFloat.size(), CvType.CV_32FC1, Scalar.all(1.0))
            Core.subtract(inv, maskFloat, inv)
            val a = Mat()
            val b = Mat()
            Core.multiply(matchedFloat, maskFloat, a)
            Core.multiply(base, inv, b)
            val sum = Mat()
            Core.add(a, b, sum)
            sum.convertTo(srcChannels[0], CvType.CV_8UC1)
            listOf(maskFloat, base, matchedFloat, inv, a, b, sum).forEach { it.release() }
        }

        val merged = Mat()
        Core.merge(srcChannels, merged)
        val out = Mat()
        Imgproc.cvtColor(merged, out, Imgproc.COLOR_Lab2RGB)
        srcChannels.forEach { it.release() }
        tgtChannels.forEach { it.release() }
        srcLab.release()
        tgtLab.release()
        merged.release()
        return out
    }

    /**
     * Classic histogram specification for single channel 8-bit mats.
     * Pixels outside [mask] keep their original value. Returns an owned mat.
     */
    fun matchHistogram(source: Mat, reference: Mat, mask: Mat, referenceMask: Mat? = mask): Mat {
        require(source.channels() == 1 && reference.channels() == 1) {
            "matchHistogram works on single channel mats (got ${source.channels()} / ${reference.channels()})"
        }
        val srcHist = FloatArray(256)
        val refHist = FloatArray(256)
        computeHistogram(source, mask, srcHist)
        // The reference histogram must be restricted to the *same* region, otherwise a region that
        // sits in shadow gets matched to the average of the whole face and the shadow is flattened.
        computeHistogram(reference, referenceMask, refHist)
        normaliseHistogram(srcHist)
        normaliseHistogram(refHist)

        val srcCdf = cdf(srcHist)
        val refCdf = cdf(refHist)
        // Lookup table: for every source level pick the level with the closest reference CDF.
        val lut = ByteArray(256)
        var refLevel = 0
        for (level in 0 until 256) {
            val target = srcCdf[level]
            while (refLevel < 255 && refCdf[refLevel] < target) refLevel++
            lut[level] = refLevel.toByte()
        }

        val bytes = ByteArray(source.rows() * source.cols())
        source.get(0, 0, bytes)
        val maskBytes = ByteArray(bytes.size)
        mask.get(0, 0, maskBytes)
        val out = bytes.copyOf()
        for (i in bytes.indices) {
            if (maskBytes[i].toInt() != 0) out[i] = lut[bytes[i].toInt() and 0xFF]
        }
        return MatUtils.maskFromBytes(out, source.cols(), source.rows())
    }

    private fun computeHistogram(source: Mat, mask: Mat?, out: FloatArray) {
        val hist = Mat()
        Imgproc.calcHist(
            listOf(source),
            MatOfInt(0),
            mask ?: Mat(),
            hist,
            MatOfInt(256),
            org.opencv.core.MatOfFloat(0f, 256f),
        )
        val buffer = FloatArray(256)
        hist.get(0, 0, buffer)
        System.arraycopy(buffer, 0, out, 0, 256)
        hist.release()
    }

    private fun normaliseHistogram(hist: FloatArray) {
        var total = 0f
        for (v in hist) total += v
        if (total <= 0f) {
            for (i in hist.indices) hist[i] = 1f / hist.size
            return
        }
        for (i in hist.indices) hist[i] /= total
    }

    private fun cdf(hist: FloatArray): FloatArray {
        val out = FloatArray(hist.size)
        var acc = 0f
        for (i in hist.indices) {
            acc += hist[i]
            out[i] = acc
        }
        return out
    }

    // =============================================================================================
    // 5. Poisson blend
    // =============================================================================================

    /** How the seamless clone should behave. */
    enum class BlendMode { NORMAL, MIXED }

    /**
     * Poisson (gradient domain) blend of [swapped] into [target] through [mask].
     *
     * MIXED_CLONE is selected automatically when the local luminance difference between the two images
     * inside the mask is large (> 12 L\*), which is exactly the night shot / harsh sun case: NORMAL_CLONE
     * would flatten the lighting, MIXED keeps the texture of the target while adopting the colour of the
     * source.
     *
     * @return the blended frame (owned). On failure the target is returned unchanged (cloned).
     */
    fun poissonBlend(
        swapped: Mat,
        target: Mat,
        mask: Mat,
        mode: BlendMode? = null,
        center: Point? = null,
    ): Mat {
        require(swapped.size() == target.size()) { "poissonBlend needs equal sizes" }
        val binary = MatUtils.normaliseMask(mask)
        val thresholded = Mat()
        Imgproc.threshold(binary, thresholded, 20.0, 255.0, Imgproc.THRESH_BINARY)
        binary.release()
        if (Core.countNonZero(thresholded) < 16) {
            thresholded.release()
            return target.clone()
        }
        val moments = Imgproc.moments(thresholded, true)
        if (moments.m00 <= 0.0) {
            thresholded.release()
            return target.clone()
        }
        val resolvedMode = mode ?: autoMode(swapped, target, thresholded)
        val useCenter = center ?: Point(moments.m10 / moments.m00, moments.m01 / moments.m00)
        val output = Mat()
        val succeeded = runCatching {
            Photo.seamlessClone(
                swapped,
                target,
                thresholded,
                useCenter,
                output,
                if (resolvedMode == BlendMode.MIXED) Photo.MIXED_CLONE else Photo.NORMAL_CLONE,
            )
            true
        }.getOrDefault(false)

        if (!succeeded || output.empty()) {
            // seamlessClone can fail on degenerate masks; fall back to a feathered alpha blend.
            val alpha = Mat()
            thresholded.convertTo(alpha, CvType.CV_32FC1, 1.0 / 255.0)
            val blurred = Mat()
            Imgproc.GaussianBlur(alpha, blurred, Size(0.0, 0.0), 7.0)
            AlphaPaste.paste(target, swapped, blurred, output)
            alpha.release()
            blurred.release()
        }
        thresholded.release()
        return output
    }

    /** Chooses NORMAL vs MIXED clone from the luminance difference (L\*) inside the mask. */
    fun autoMode(swapped: Mat, target: Mat, mask: Mat): BlendMode {
        val a = Mat()
        val b = Mat()
        Imgproc.cvtColor(swapped, a, Imgproc.COLOR_RGB2Lab)
        Imgproc.cvtColor(target, b, Imgproc.COLOR_RGB2Lab)
        val la = Mat()
        val lb = Mat()
        Core.extractChannel(a, la, 0)
        Core.extractChannel(b, lb, 0)
        // L is 0..255 in the 8-bit encoding; convert to L* 0..100 before comparing with the threshold.
        val diff = abs(Core.mean(la, mask).`val`[0] - Core.mean(lb, mask).`val`[0]) * 100.0 / 255.0
        listOf(a, b, la, lb).forEach { it.release() }
        return if (diff > MIXED_CLONE_THRESHOLD) BlendMode.MIXED else BlendMode.NORMAL
    }

    // =============================================================================================
    // 6. Temporal consistency (video)
    // =============================================================================================

    /**
     * EMA update for the target colour moments: `mean_cur = mean_prev * 0.85 + mean_new * 0.15`.
     * The very first frame initialises the state.
     */
    fun temporalMean(faceId: Int, measured: LabMoments, alpha: Double = 0.15): LabMoments {
        val previous = temporal[faceId]
        if (previous == null) {
            temporal[faceId] = measured
            return measured
        }
        val blended = previous.lerp(measured, alpha)
        temporal[faceId] = blended
        return blended
    }

    /** Forgets the temporal state of one face, or of all faces (new video, new face). */
    fun resetTemporal(faceId: Int? = null) {
        if (faceId == null) temporal.clear() else temporal.remove(faceId)
    }

    /** Current smoothed moments (tests + UI readout). */
    fun temporalState(faceId: Int): LabMoments? = temporal[faceId]

    /**
     * Applies the smoothed target moments to [source]; used per video frame so the skin tone does not
     * breathe from frame to frame.
     */
    fun applyTemporal(source: Mat, target: Mat, mask: Mat, faceId: Int, alpha: Double = 0.15): Mat {
        val measured = measureLab(target, mask)
        val smoothed = temporalMean(faceId, measured, alpha)
        return ethnicityAdaptiveLabTransfer(targetMoments = smoothed, source = source, target = target, mask = mask)
    }

    // =============================================================================================
    // 7. Full pass
    // =============================================================================================

    /**
     * Complete photo/video colour pass: mask -> normalise (target) -> LAB transfer -> regional
     * histogram -> Poisson.
     *
     * @param swapped frame containing the swapped face (RGB)
     * @param target original frame (RGB)
     * @param mesh mesh of the face in the shared coordinate space
     * @return the colour matched frame (owned)
     */
    fun blendFaces(
        swapped: Mat,
        target: Mat,
        mesh: com.swapstudio.ai.core.mesh.MeshFace,
        options: BlendOptions = BlendOptions(),
        faceId: Int = 0,
    ): Mat {
        val width = swapped.cols()
        val height = swapped.rows()
        val mask = createFeatheredSkinMask(mesh, width, height, options.parserMask)
        try {
            // Normalise the *swapped* frame, never the target. CLAHE is a local operator: run on the
            // target it would lift the shadow side of the face and destroy the direction of the light
            // (the exact artefact the regional histogram step is supposed to fix). The swapper output
            // comes from a 128x128 crop and is the image that needs the exposure/cast correction.
            val normalisedSource = normalise(swapped, mask = mask)

            // Statistics of the *raw* target drive the transfer, so the swapped face adopts the
            // lighting of the scene instead of an equalised version of it.
            val sourceMoments = measureLab(normalisedSource, mask)
            val targetMoments = if (options.useTemporal) {
                temporalMean(faceId, measureLab(target, mask), options.temporalAlpha)
            } else {
                measureLab(target, mask)
            }

            var current = ethnicityAdaptiveLabTransfer(
                source = normalisedSource,
                target = target,
                mask = mask,
                sourceMoments = sourceMoments,
                targetMoments = targetMoments,
                texturePreservation = options.texturePreservation,
            )
            normalisedSource.release()
            if (options.useRegionalHistogram) {
                val regional = regionalHistogramMatch(current, target, mesh.landmarks, blend = options.regionalBlend)
                current.release()
                current = regional
            }
            if (options.usePoisson) {
                val blended = poissonBlend(current, target, mask, options.poissonMode)
                current.release()
                current = blended
            }
            return current
        } finally {
            mask.release()
        }
    }

    /** Tuning options for [blendFaces]. */
    data class BlendOptions(
        /** Skin mask from the face parser (optional, `CV_8UC1`, frame space). */
        val parserMask: Mat? = null,
        val useRegionalHistogram: Boolean = true,
        val regionalBlend: Double = 0.75,
        val usePoisson: Boolean = true,
        val poissonMode: BlendMode? = null,
        val useTemporal: Boolean = false,
        val temporalAlpha: Double = 0.15,
        val texturePreservation: Double = 0.12,
    )

    companion object {
        /** Luminance difference (L\*) above which MIXED_CLONE is preferred. */
        const val MIXED_CLONE_THRESHOLD = 12.0

        /** Chroma transfer strengths, exposed for tests and documentation. */
        const val CHROMA_STRENGTH_DARK = 0.30
        const val CHROMA_STRENGTH_MEDIUM = 0.45
        const val CHROMA_STRENGTH_LIGHT = 0.60

        /** Standard deviation ratio clamp used by the L transfer. */
        const val L_RATIO_MIN = 0.6
        const val L_RATIO_MAX = 1.6

        /** True when the two standard deviations are close enough for a pure transfer. */
        fun contrastCompatible(a: LabMoments, b: LabMoments): Boolean {
            val ratio = b.std[0] / a.std[0]
            return ratio in L_RATIO_MIN..L_RATIO_MAX
        }

        /** Euclidean distance between two LAB means (tests). */
        fun distance(a: LabMoments, b: LabMoments): Double {
            var sum = 0.0
            for (i in 0 until 3) sum += (a.mean[i] - b.mean[i]) * (a.mean[i] - b.mean[i])
            return sqrt(sum)
        }
    }
}

/** Feathered alpha paste used as the Poisson fallback. */
internal object AlphaPaste {
    fun paste(target: Mat, source: Mat, maskFloat: Mat, out: Mat) {
        val mask3 = Mat()
        Imgproc.cvtColor(maskFloat, mask3, Imgproc.COLOR_GRAY2RGB)
        val inv = Mat(mask3.size(), CvType.CV_32FC3, Scalar.all(1.0))
        Core.subtract(inv, mask3, inv)
        val a = Mat()
        val b = Mat()
        val srcFloat = Mat()
        source.convertTo(srcFloat, CvType.CV_32FC3)
        val tgtFloat = Mat()
        target.convertTo(tgtFloat, CvType.CV_32FC3)
        Core.multiply(srcFloat, mask3, a)
        Core.multiply(tgtFloat, inv, b)
        val sum = Mat()
        Core.add(a, b, sum)
        sum.convertTo(out, CvType.CV_8UC3)
        listOf(mask3, inv, a, b, srcFloat, tgtFloat, sum).forEach { it.release() }
    }
}
