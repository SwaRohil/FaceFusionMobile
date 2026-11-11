package com.swapstudio.ai.core.geometry

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * Geometry helpers for the 478 point MediaPipe Face Mesh.
 *
 * All landmark indices below are **MediaPipe Face Mesh** indices (468 mesh points + 10 iris points),
 * *not* the old 68 point dlib/face-api model. Keeping the naming close to the official mesh map makes
 * the region masks easy to audit.
 */
object FaceGeometry {

    /** 468 base mesh points + 2 x 5 iris points. */
    const val MESH_POINT_COUNT = 478

    // ---- rings / regions -------------------------------------------------------------------
    /** Face silhouette, clockwise starting at the forehead centre. */
    val FACE_OVAL = intArrayOf(
        10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378,
        400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109,
    )

    /** Subject's right eye (image left) closed ring. */
    val RIGHT_EYE_RING = intArrayOf(
        33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246,
    )

    /** Subject's left eye (image right) closed ring. */
    val LEFT_EYE_RING = intArrayOf(
        263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466,
    )

    val LIPS_OUTER = intArrayOf(
        61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185,
    )

    val LIPS_INNER = intArrayOf(
        78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191,
    )

    /** Nostril openings; used to cut the "skin" mask around the nose. */
    val NOSTRILS = intArrayOf(98, 240, 97, 2, 326, 327, 168, 6, 197, 195, 5, 4, 1, 19, 94)

    val RIGHT_EYEBROW = intArrayOf(70, 63, 105, 66, 107, 55, 65, 52, 53, 46)
    val LEFT_EYEBROW = intArrayOf(300, 293, 334, 296, 336, 285, 295, 282, 283, 276)

    /** Iris centres (10 extra points appended after the 468 mesh points). */
    const val RIGHT_IRIS_CENTER = 468
    const val LEFT_IRIS_CENTER = 473

    // Eye corner / lid landmarks used for the five point alignment template.
    const val RIGHT_EYE_OUTER = 33
    const val RIGHT_EYE_INNER = 133
    const val LEFT_EYE_INNER = 362
    const val LEFT_EYE_OUTER = 263
    const val NOSE_TIP = 1
    const val MOUTH_LEFT = 61
    const val MOUTH_RIGHT = 291
    const val CHIN = 152
    const val JAW_LEFT = 234
    const val JAW_RIGHT = 454
    const val FOREHEAD = 10

    /** Facial regions used by the colour pipeline and the occlusion logic. */
    enum class Region { FOREHEAD, LEFT_CHEEK, RIGHT_CHEEK, CHIN_NOSE }

    /**
     * Turns MediaPipe's *normalised* landmarks into pixel coordinates.
     *
     * @param normalized flat list of `x, y, z` triples in 0..1 space (MediaPipe returns one list per
     *   face, each landmark being a NormalizedLandmark with x/y/z).
     */
    fun toPixels(normalized: FloatArray, width: Int, height: Int): Array<Point> {
        require(normalized.size % 3 == 0) { "landmark array must be x,y,z triples" }
        val count = normalized.size / 3
        return Array(count) { i ->
            Point(
                normalized[i * 3].toDouble() * width,
                normalized[i * 3 + 1].toDouble() * height,
            )
        }
    }

    /** Face box from the mesh silhouette (tight and pose aware, unlike the detector box). */
    fun boundsFromMesh(points: Array<Point>): DoubleArray {
        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (index in FACE_OVAL) {
            if (index >= points.size) continue
            val p = points[index]
            if (p.x < minX) minX = p.x
            if (p.y < minY) minY = p.y
            if (p.x > maxX) maxX = p.x
            if (p.y > maxY) maxY = p.y
        }
        if (minX > maxX) { // degenerate mesh, fall back to every point
            for (p in points) {
                minX = minOf(minX, p.x); minY = minOf(minY, p.y)
                maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y)
            }
        }
        return doubleArrayOf(minX, minY, maxX - minX, maxY - minY)
    }

    /** Eye centres: uses the iris points when present (more stable than the lid ring centre). */
    fun eyeCentres(points: Array<Point>): Pair<Point, Point> {
        val right = if (points.size > RIGHT_IRIS_CENTER) points[RIGHT_IRIS_CENTER] else midpoint(points[RIGHT_EYE_OUTER], points[RIGHT_EYE_INNER])
        val left = if (points.size > LEFT_IRIS_CENTER) points[LEFT_IRIS_CENTER] else midpoint(points[LEFT_EYE_OUTER], points[LEFT_EYE_INNER])
        return right to left
    }

    /** Mouth corners from the outer lip ring. */
    fun mouthCorners(points: Array<Point>): Pair<Point, Point> = points[MOUTH_LEFT] to points[MOUTH_RIGHT]

    /**
     * The five alignment points FaceFusion/InsightFace use, derived from the 478 point mesh:
     * `[right eye, left eye, nose tip, mouth right, mouth left]` in **image** coordinates.
     */
    fun fivePoint(points: Array<Point>): MatOfPoint2f {
        val (rightEye, leftEye) = eyeCentres(points)
        val (mouthLeft, mouthRight) = mouthCorners(points)
        return MatOfPoint2f(rightEye, leftEye, points[NOSE_TIP], mouthRight, mouthLeft)
    }

    /** Eye-line roll in degrees (positive = head tilted clockwise on screen). */
    fun rollDegrees(points: Array<Point>): Float {
        val (right, left) = eyeCentres(points)
        return Math.toDegrees(atan2(left.y - right.y, left.x - right.x)).toFloat()
    }

    /**
     * Approximate yaw / pitch in degrees.
     *
     * The estimate uses ratios between stable mesh points instead of a PnP solve, which keeps it
     * cheap enough to run per video frame:
     *  * yaw: horizontal offset of the nose tip from the eye-line centre, normalised by the
     *    inter-ocular distance;
     *  * pitch: vertical position of the nose tip between the eye line and the chin.
     */
    fun yawPitchDegrees(points: Array<Point>): Pair<Float, Float> {
        val (rightEye, leftEye) = eyeCentres(points)
        val eyeMidX = (rightEye.x + leftEye.x) / 2.0
        val eyeMidY = (rightEye.y + leftEye.y) / 2.0
        val interOcular = max(1.0, hypot(leftEye.x - rightEye.x, leftEye.y - rightEye.y))
        val nose = points[NOSE_TIP]
        val chin = points[CHIN]
        val yaw = Math.toDegrees(atan2(nose.x - eyeMidX, interOcular)).toFloat()
        val eyeToChin = max(1.0, chin.y - eyeMidY)
        val pitchRatio = ((nose.y - eyeMidY) / eyeToChin).coerceIn(-0.5, 1.5)
        val pitch = ((pitchRatio - 0.45) * 120.0).toFloat()
        return yaw to pitch
    }

    /** True when the face is too small for a hyper realistic result (user requirement: 80 px). */
    fun isTooSmall(bounds: DoubleArray, minimumPx: Int = 80): Boolean =
        bounds[2] < minimumPx || bounds[3] < minimumPx

    // ---------------------------------------------------------------------------------------
    // Mask building
    // ---------------------------------------------------------------------------------------

    /** Fills the polygon described by [indices] into a new `CV_8UC1` mask (255 inside). */
    fun regionMask(points: Array<Point>, indices: IntArray, width: Int, height: Int): Mat {
        val mask = Mat.zeros(height, width, org.opencv.core.CvType.CV_8UC1)
        val contour = MatOfPoint(*indices.filter { it < points.size }.map { points[it] }.toTypedArray())
        if (contour.total() >= 3) Imgproc.fillPoly(mask, listOf(contour), Scalar(255.0))
        contour.release()
        return mask
    }

    /** Ellipse mask covering the eye/lid area — used to feather the eye refinement. */
    fun eyeMask(points: Array<Point>, indices: IntArray, width: Int, height: Int, grow: Double = 1.15): Mat {
        val mask = Mat.zeros(height, width, org.opencv.core.CvType.CV_8UC1)
        val centre = centroid(indices.filter { it < points.size }.map { points[it] })
        var radiusX = 1.0
        var radiusY = 1.0
        for (index in indices) {
            if (index >= points.size) continue
            radiusX = max(radiusX, abs(points[index].x - centre.x))
            radiusY = max(radiusY, abs(points[index].y - centre.y))
        }
        Imgproc.ellipse(
            mask,
            centre,
            Size(radiusX * grow, radiusY * grow),
            rollDegrees(points).toDouble(),
            0.0,
            360.0,
            Scalar(255.0),
            -1,
        )
        return mask
    }

    /** Polygon mask from an arbitrary list of pixel points. */
    fun polygonMask(points: List<Point>, width: Int, height: Int): Mat {
        val mask = Mat.zeros(height, width, org.opencv.core.CvType.CV_8UC1)
        if (points.size >= 3) {
            val contour = MatOfPoint(*points.toTypedArray())
            Imgproc.fillPoly(mask, listOf(contour), Scalar(255.0))
            contour.release()
        }
        return mask
    }

    /** Centroid of the given points. */
    fun centroid(points: List<Point>): Point {
        if (points.isEmpty()) return Point(0.0, 0.0)
        var x = 0.0
        var y = 0.0
        for (p in points) { x += p.x; y += p.y }
        return Point(x / points.size, y / points.size)
    }

    /** Midpoint helper. */
    fun midpoint(a: Point, b: Point): Point = Point((a.x + b.x) / 2.0, (a.y + b.y) / 2.0)

    /** Selects the landmarks for a [Region] as a polygon (used by [RegionalColorTransfer]). */
    fun regionPolygon(points: Array<Point>, region: Region): List<Point> = when (region) {
        // Forehead: band between the brow line and the hairline.
        Region.FOREHEAD -> listOf(
            points[10], points[338], points[297], points[332], points[284], points[251],
            points[21], points[54], points[103], points[67], points[109], points[151],
        )
        // Cheeks: either side of the nose, under the eye, above the jaw.
        Region.LEFT_CHEEK -> listOf(
            points[50], points[101], points[100], points[47], points[114], points[128],
            points[234], points[93], points[132], points[58], points[172], points[136],
        )
        Region.RIGHT_CHEEK -> listOf(
            points[280], points[330], points[329], points[277], points[343], points[357],
            points[454], points[323], points[361], points[288], points[397], points[365],
        )
        // Chin + nose column: catches the shadow cast by the nose and the jaw.
        Region.CHIN_NOSE -> listOf(
            points[1], points[2], points[98], points[327], points[168], points[6],
            points[197], points[195], points[5], points[4], points[175], points[199],
            points[200], points[18], points[152], points[377], points[400], points[378],
        )
    }
}
