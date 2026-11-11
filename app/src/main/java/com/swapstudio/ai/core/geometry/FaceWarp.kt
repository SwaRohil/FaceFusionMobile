package com.swapstudio.ai.core.geometry

import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Face alignment templates and crop/paste-back helpers.
 *
 * These are the classic InsightFace "arcface" five point templates, expressed as **normalised**
 * coordinates so they can be scaled to any crop size. FaceFusion uses the very same numbers; keeping
 * them identical is what makes `inswapper_128_fp16.onnx` behave the way it was trained.
 *
 * The five points are ordered `[right eye, left eye, nose tip, mouth right, mouth left]` in image
 * coordinates (i.e. mirrored horizontally on the screen for the observer).
 */
object FaceWarp {

    /** 112x112 template used to compute ArcFace embeddings (recognition / identity score). */
    val ARCFACE_112_V2 = doubleArrayOf(
        0.34191607, 0.46157411,
        0.65653393, 0.45983393,
        0.50022500, 0.64050536,
        0.37097589, 0.82469196,
        0.63151696, 0.82325089,
    )

    /** 128x128 template baked into `inswapper_128` / `inswapper_128_fp16`. */
    val ARCFACE_128_V2 = doubleArrayOf(
        0.36167656, 0.40387734,
        0.63696719, 0.40235469,
        0.50019687, 0.56044219,
        0.38710391, 0.72160547,
        0.61507734, 0.72034453,
    )

    /** 512x512 template used by GFPGAN / CodeFormer face restoration. */
    val FFHQ_512 = doubleArrayOf(
        0.37691676, 0.46864664,
        0.62285697, 0.46912813,
        0.50123859, 0.61331904,
        0.39308822, 0.72541100,
        0.61150205, 0.72490465,
    )

    /**
     * Similarity transform (rotation + uniform scale + translation) that maps the five landmark
     * points of a face onto [template] scaled to [size] x [size].
     *
     * Partial affine (4 DoF) is used on purpose: a full homography would bend the jaw and the eyes of
     * strongly rotated faces, which is exactly the "warped teeth/eyes" artefact we must avoid.
     */
    fun estimateAffine(landmarks: MatOfPoint2f, template: DoubleArray, size: Int): Mat {
        val target = MatOfPoint2f(
            *Array(5) { i -> Point(template[i * 2] * size, template[i * 2 + 1] * size) },
        )
        // RANSAC with a generous threshold: five points give us no redundancy, but the robust
        // estimator still rejects the odd misdetected corner instead of skewing the whole warp.
        val inliers = Mat()
        val matrix = Calib3d.estimateAffinePartial2D(
            landmarks, target, inliers, Calib3d.RANSAC, 100.0, 2000, 0.99, 10,
        ) ?: Mat.eye(2, 3, CvType.CV_64FC1)
        target.release()
        inliers.release()
        return matrix
    }

    /** Warps the face of [frame] (RGB or BGR, CV_8UC3) into a [size] x [size] crop. */
    fun warp(frame: Mat, affine: Mat, size: Int, borderReplicate: Boolean = true): Mat {
        val crop = Mat()
        Imgproc.warpAffine(
            frame,
            crop,
            affine,
            Size(size.toDouble(), size.toDouble()),
            Imgproc.INTER_AREA,
            org.opencv.core.Core.BORDER_REPLICATE,
            org.opencv.core.Scalar.all(0.0),
        )
        return crop
    }

    /**
     * Warps a [size] x [size] crop and its mask back into the target frame and alpha-blends it.
     *
     * `frame` is modified in place (CV_8UC3). [mask] must be `CV_32FC1` or `CV_8UC1` in *crop* space;
     * it is warped with the inverse affine and feathered by the caller.
     */
    fun pasteBack(frame: Mat, crop: Mat, mask: Mat, affine: Mat) {
        val inverse = Mat()
        Imgproc.invertAffineTransform(affine, inverse)
        val frameSize = Size(frame.cols().toDouble(), frame.rows().toDouble())

        val inverseMask = Mat()
        Imgproc.warpAffine(mask, inverseMask, inverse, frameSize, Imgproc.INTER_LINEAR)
        val inverseCrop = Mat()
        Imgproc.warpAffine(
            crop, inverseCrop, inverse, frameSize, Imgproc.INTER_LINEAR,
            org.opencv.core.Core.BORDER_REPLICATE,
        )

        // Normalise the mask to 0..1 float and make sure it has three channels for the blend.
        val maskFloat = Mat()
        when (inverseMask.type()) {
            CvType.CV_8UC1 -> inverseMask.convertTo(maskFloat, CvType.CV_32FC1, 1.0 / 255.0)
            CvType.CV_32FC1 -> inverseMask.copyTo(maskFloat)
            else -> inverseMask.convertTo(maskFloat, CvType.CV_32FC1)
        }
        val mask3 = Mat()
        Imgproc.cvtColor(maskFloat, mask3, Imgproc.COLOR_GRAY2RGB)

        val cropFloat = Mat()
        inverseCrop.convertTo(cropFloat, CvType.CV_32FC3)
        val frameFloat = Mat()
        frame.convertTo(frameFloat, CvType.CV_32FC3)

        // frame = mask * crop + (1 - mask) * frame
        val oneMinus = Mat(mask3.size(), CvType.CV_32FC3, org.opencv.core.Scalar.all(1.0))
        org.opencv.core.Core.subtract(oneMinus, mask3, oneMinus)
        val blended = Mat()
        org.opencv.core.Core.add(
            multiplyPerPixel(cropFloat, mask3),
            multiplyPerPixel(frameFloat, oneMinus),
            blended,
        )
        blended.convertTo(frame, CvType.CV_8UC3)

        listOf(inverse, inverseMask, inverseCrop, maskFloat, mask3, cropFloat, frameFloat, oneMinus, blended)
            .forEach { it.release() }
    }

    /** Element-wise multiplication of two equal-shape mats (kept separate for readability). */
    private fun multiplyPerPixel(a: Mat, b: Mat): Mat {
        val out = Mat()
        org.opencv.core.Core.multiply(a, b, out)
        return out
    }

    /**
     * Builds the soft box mask used after face restoration: full weight in the middle, feathered
     * towards the border so the enhanced crop never shows a rectangular edge.
     *
     * @param border fraction of the box that is feathered away on each side (0..0.5)
     */
    fun boxMask(size: Int, border: Double = 0.08): Mat {
        val mask = Mat.ones(size, size, CvType.CV_32FC1)
        val borderPx = (size * border).toInt().coerceAtLeast(2)
        val inner = Mat.ones(size - borderPx * 2, size - borderPx * 2, CvType.CV_32FC1)
        val canvas = Mat.zeros(size, size, CvType.CV_32FC1)
        inner.copyTo(canvas.submat(borderPx, size - borderPx, borderPx, size - borderPx))
        // A generous blur turns the hard edge into a gradient.
        Imgproc.GaussianBlur(canvas, mask, Size(0.0, 0.0), borderPx / 2.0)
        inner.release()
        canvas.release()
        val normalised = Mat()
        org.opencv.core.Core.normalize(mask, normalised, 0.0, 1.0, org.opencv.core.Core.NORM_MINMAX)
        mask.release()
        return normalised
    }

    /** Convenience: builds a five point mat from x/y pairs. */
    fun points(vararg xy: Double): MatOfPoint2f {
        require(xy.size == 10) { "expected five (x, y) pairs" }
        return MatOfPoint2f(*Array(5) { i -> Point(xy[i * 2], xy[i * 2 + 1]) })
    }
}
