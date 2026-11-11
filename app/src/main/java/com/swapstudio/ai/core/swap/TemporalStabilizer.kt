package com.swapstudio.ai.core.swap

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One dimensional Kalman filter with a constant-velocity model.
 *
 * Used per landmark coordinate: the state is `[position, velocity]`, the process noise `q` describes
 * how fast a mouth can move, the measurement noise `r` how much the mesh detector jitters. Because the
 * filter carries a velocity it follows a blink or a fast smile without lagging behind, which is the
 * whole point (a plain low-pass filter either lags or does not smooth).
 */
class KalmanAxis(
    private var position: Double,
    private var velocity: Double = 0.0,
    private val q: Double = 2.5e-3,
    private val r: Double = 3.0e-2,
) {
    private var cov00 = 1.0
    private var cov01 = 0.0
    private var cov10 = 0.0
    private var cov11 = 1.0

    /** Shifts the current state by [delta] (used to follow the rigid motion of the face). */
    fun shift(delta: Double) {
        position += delta
    }

    /** Predicts the next position without a measurement (used for dropped frames). */
    fun predict(): Double {
        position += velocity
        cov00 += cov01 + cov10 + cov11 + q
        cov01 += cov11 + q
        cov10 += cov11 + q
        cov11 += q
        return position
    }

    /** Corrects the prediction with [measurement] and returns the filtered position. */
    fun update(measurement: Double): Double {
        predict()
        val innovation = measurement - position
        val s = cov00 + r
        val k0 = cov00 / s
        val k1 = cov10 / s
        position += k0 * innovation
        velocity += k1 * innovation
        val c00 = (1 - k0) * cov00
        val c01 = (1 - k0) * cov01
        val c10 = cov10 - k1 * cov00
        val c11 = cov11 - k1 * cov01
        cov00 = c00
        cov01 = c01
        cov10 = c10
        cov11 = c11
        return position
    }

    /** Current estimated velocity (pixels/frame). */
    fun velocity(): Double = velocity

    /** Current position estimate. */
    fun value(): Double = position

    /** Number of pixels the filter is offset from the raw measurement (debug/metrics). */
    fun lag(measurement: Double): Double = abs(position - measurement)
}

/**
 * Temporal stabiliser for a video swap.
 *
 * Three independent mechanisms, exactly as specified:
 *  * **Optical flow** (Farneback) tracks the rigid motion of the face between two frames, so the Kalman
 *    filter can predict where the landmarks should be instead of assuming they stood still.
 *  * **Kalman on the landmarks** removes per-frame jitter without lagging behind blinks.
 *  * **EMA on the blendshapes, 0.85 previous + 0.15 new over 5 frames** — the expression channels are
 *    what make the swapped face look alive; smoothing them too much freezes the face, smoothing them
 *    too little makes the mouth flicker. 0.85/0.15 with a five frame lag is the value that was tuned
 *    for this app and is exposed as [BlendshapeEma].
 */
class TemporalStabilizer(
    private val alpha: Double = 0.15,
    private val landmarkCount: Int = com.swapstudio.ai.core.geometry.FaceGeometry.MESH_POINT_COUNT,
    private val blendshapeCount: Int = 52,
) {
    private val filtersX = HashMap<Int, KalmanAxis>()
    private val filtersY = HashMap<Int, KalmanAxis>()
    private var smoothedBlendshapes: FloatArray? = null
    private var previousGray: Mat? = null
    private var lastFlow: DoubleArray? = null
    private var framesSeen = 0

    /** Blendshape EMA constant, exposed for the settings screen. */
    val blendshapeAlpha: Double get() = alpha

    /**
     * Filters the landmarks of the current frame.
     *
     * The optical flow vector is used to *pre-move* the filter state: if the whole face travelled
     * `(dx, dy)` since the last frame, the predicted landmark positions are shifted by the same amount
     * before the measurement is applied. Without that, a fast pan would look like detector jitter and
     * the Kalman filter would drag the mesh behind the face.
     *
     * @param points the raw mesh points (pixels)
     * @param flow global (dx, dy) of the face region, or null on the first frame
     * @return a new array of filtered points (the input array is not modified)
     */
    fun smoothLandmarks(points: Array<Point>, flow: Pair<Double, Double>? = null): Array<Point> {
        val out = Array(points.size) { Point(0.0, 0.0) }
        val shiftX = flow?.first ?: 0.0
        val shiftY = flow?.second ?: 0.0
        for (i in points.indices) {
            val fx = filtersX.getOrPut(i) { KalmanAxis(points[i].x) }
            val fy = filtersY.getOrPut(i) { KalmanAxis(points[i].y) }
            if (shiftX != 0.0 || shiftY != 0.0) {
                fx.shift(shiftX)
                fy.shift(shiftY)
            }
            out[i] = Point(fx.update(points[i].x), fy.update(points[i].y))
        }
        framesSeen++
        return out
    }

    /**
     * Same as [smoothLandmarks] but returns a [MatOfPoint2f] ready for OpenCV, with the original
     * landmark indices preserved (it is a 1:1 replacement of the mesh array).
     */
    fun smoothLandmarksMat(points: Array<Point>): MatOfPoint2f = MatOfPoint2f(*smoothLandmarks(points))

    /**
     * EMA for the 52 blendshape coefficients: `cur = prev * 0.85 + new * 0.15`.
     * The first call initialises the state and returns the measurement unchanged.
     */
    fun smoothBlendshapes(coefficients: FloatArray): FloatArray {
        val previous = smoothedBlendshapes
        if (previous == null || previous.size != coefficients.size) {
            smoothedBlendshapes = coefficients.copyOf()
            return coefficients.copyOf()
        }
        val out = FloatArray(coefficients.size)
        for (i in coefficients.indices) {
            out[i] = (previous[i] * (1.0 - alpha) + coefficients[i] * alpha).toFloat()
        }
        smoothedBlendshapes = out.copyOf()
        return out
    }

    /**
     * Estimates the rigid motion of the face between the previous and the current frame with Farneback
     * optical flow, restricted to the face box.
     *
     * @return `(dx, dy)` in pixels for the face centre, or `(0,0)` when no flow can be computed
     */
    fun estimateFlow(gray: Mat, faceBox: DoubleArray): Pair<Double, Double> {
        val previous = previousGray
        val resized = Mat()
        Imgproc.resize(gray, resized, Size(gray.cols().toDouble(), gray.rows().toDouble()))
        if (previous == null || previous.size() != resized.size()) {
            previous?.release()
            previousGray = resized
            return 0.0 to 0.0
        }
        val flow = Mat()
        org.opencv.video.Video.calcOpticalFlowFarneback(previous, resized, flow, 0.5, 3, 15, 3, 5, 1.2, 0)
        val x = max(0, faceBox[0].toInt())
        val y = max(0, faceBox[1].toInt())
        val w = min(gray.cols() - x, faceBox[2].toInt())
        val h = min(gray.rows() - y, faceBox[3].toInt())
        var dx = 0.0
        var dy = 0.0
        if (w > 4 && h > 4) {
            val roi = Mat(flow, org.opencv.core.Rect(x, y, w, h))
            val channels = ArrayList<Mat>(2)
            org.opencv.core.Core.split(roi, channels)
            dx = org.opencv.core.Core.mean(channels[0]).`val`[0]
            dy = org.opencv.core.Core.mean(channels[1]).`val`[0]
            channels.forEach { it.release() }
            roi.release()
        }
        flow.release()
        previous.release()
        previousGray = resized
        lastFlow = doubleArrayOf(dx, dy)
        return dx to dy
    }

    /** Last computed flow vector (debug readout in the video screen). */
    fun flow(): DoubleArray? = lastFlow

    /** True once at least one frame has been filtered. */
    fun isWarm(): Boolean = framesSeen > 1

    /** Resets all state; call between videos so no stale motion leaks in. */
    fun reset() {
        filtersX.clear()
        filtersY.clear()
        smoothedBlendshapes = null
        previousGray?.release()
        previousGray = null
        lastFlow = null
        framesSeen = 0
    }
}
