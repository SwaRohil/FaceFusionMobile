package com.swapstudio.ai.core.detection

import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import com.swapstudio.ai.core.inference.OrtEngine
import com.swapstudio.ai.core.inference.SessionSpec
import com.swapstudio.ai.core.models.ModelManager
import com.swapstudio.ai.core.util.BitmapUtils
import com.swapstudio.ai.core.util.MatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One detected face.
 *
 * @param box left, top, width, height in pixels of the analysed image
 * @param score detector confidence in 0..1
 * @param landmarks five points in image pixels, ordered
 *   `[right eye, left eye, nose tip, mouth right, mouth left]`
 */
data class DetectedFace(
    val box: DoubleArray,
    val score: Float,
    val landmarks: Array<Point>,
) {
    val centerX: Double get() = box[0] + box[2] / 2.0
    val centerY: Double get() = box[1] + box[3] / 2.0
    val widthPx: Double get() = box[2]
    val heightPx: Double get() = box[3]
    val area: Double get() = box[2] * box[3]

    /** Five point array as the flat `[x0,y0,...,x4,y4]` float layout OpenCV expects. */
    fun flatLandmarks(): FloatArray {
        val flat = FloatArray(landmarks.size * 2)
        landmarks.forEachIndexed { index, point ->
            flat[index * 2] = point.x.toFloat()
            flat[index * 2 + 1] = point.y.toFloat()
        }
        return flat
    }
}

/** Detection tuning. Defaults follow InsightFace's `det_10g` configuration. */
data class DetectionConfig(
    val inputWidth: Int = 640,
    val inputHeight: Int = 640,
    val scoreThreshold: Float = 0.5f,
    val nmsThreshold: Float = 0.4f,
    /** Faces smaller than this (in pixels of the analysed image) are reported but flagged. */
    val minimumFacePx: Int = 80,
    val maxFaces: Int = 20,
)

/**
 * SCRFD (Sample and Computation Redistribution for Face Detection) wrapper.
 *
 * The shipped model is InsightFace `det_10g.onnx` from the buffalo_l pack:
 *  * input  `[1, 3, 640, 640]` float32 RGB, normalised `(x - 127.5) / 128`, NCHW.
 *  * outputs 9 tensors in three groups of three strides (8, 16, 32):
 *    `score` `[1, N, 1]`, `bbox` `[1, N, 4]` (distance to the anchor), `kps` `[1, N, 10]`.
 *    Tensors are grouped either as `[s8, s16, s32, bbox8, bbox16, bbox32, kps8, ...]` (batched) or
 *    interleaved `[s8, b8, k8, s16, ...]`; both are supported by inspecting the shapes.
 *
 * Multi face support, confidence filtering and NMS are implemented here, plus the two helpers the UI
 * needs: "largest face" and "sharpest face".
 */
@Singleton
class FaceDetector @Inject constructor(
    private val models: ModelManager,
) {
    private val json = org.json.JSONObject()

    /** Detects every face in [bitmap]. */
    suspend fun detect(bitmap: Bitmap, config: DetectionConfig = DetectionConfig()): List<DetectedFace> =
        withContext(Dispatchers.Default) {
            val rgb = BitmapUtils.bitmapToRgb(bitmap)
            try {
                detect(rgb, config)
            } finally {
                rgb.release()
            }
        }

    /** Detects every face in an RGB `CV_8UC3` mat. */
    suspend fun detect(rgb: Mat, config: DetectionConfig = DetectionConfig()): List<DetectedFace> =
        withContext(Dispatchers.Default) {
            val file = models.detectorFile()
            if (!file.exists()) return@withContext emptyList()
            val spec = SessionSpec(threads = 4, kind = com.swapstudio.ai.core.inference.EpKind.CPU)
            val session = models.sessionFor(file, spec)
            try {
                runDetection(session, rgb, config)
            } finally {
                // Sessions stay cached in ModelManager; nothing to close here.
            }
        }

    /** Synchronous entry point used by the pipelines (which already own a CPU session). */
    fun detectBlocking(session: OrtSession, rgb: Mat, config: DetectionConfig = DetectionConfig()): List<DetectedFace> =
        runDetection(session, rgb, config)

    private fun runDetection(session: OrtSession, rgb: Mat, config: DetectionConfig): List<DetectedFace> {
        val imageWidth = rgb.cols()
        val imageHeight = rgb.rows()

        // ---- 1. Letterbox resize so we never squeeze the face proportions -------------------
        val scale = min(config.inputWidth.toDouble() / imageWidth, config.inputHeight.toDouble() / imageHeight)
        val newWidth = (imageWidth * scale).toInt().coerceAtLeast(1)
        val newHeight = (imageHeight * scale).toInt().coerceAtLeast(1)
        val resized = Mat()
        Imgproc.resize(rgb, resized, Size(newWidth.toDouble(), newHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val canvas = Mat(config.inputHeight, config.inputWidth, CvType.CV_8UC3, org.opencv.core.Scalar.all(0.0))
        val padX = (config.inputWidth - newWidth) / 2
        val padY = (config.inputHeight - newHeight) / 2
        resized.copyTo(canvas.submat(padY, padY + newHeight, padX, padX + newWidth))

        // SCRFD wants RGB, mean 127.5 / std 128 in NCHW.
        val nchw = MatUtils.toNchwFloat(
            canvas,
            mean = floatArrayOf(127.5f, 127.5f, 127.5f),
            std = floatArrayOf(1f / 128f, 1f / 128f, 1f / 128f),
            scale = 1f,
            bgr = false,
        )
        resized.release()
        canvas.release()

        val shape = longArrayOf(1, 3, config.inputHeight.toLong(), config.inputWidth.toLong())
        val tensor = OnnxTensor.createTensor(OrtEngine.env(), java.nio.FloatBuffer.wrap(nchw), shape)
        val inputName = session.inputNames.first()

        val scoresList = mutableListOf<Float>()
        val boxesList = mutableListOf<Float>()
        val keypointsList = mutableListOf<Float>()

        session.run(mapOf(inputName to tensor)).use { result ->
            // Group outputs by their last dimension: 1 -> score, 4 -> bbox, 10 -> keypoints.
            val fmc = 3 // anchor counts per location for SCRFD 8/16/32
            val strideData = ArrayList<Pair<Int, FloatArray>>()
            val bboxData = ArrayList<Pair<Int, FloatArray>>()
            val kpsData = ArrayList<Pair<Int, FloatArray>>()
            for (i in 0 until result.size()) {
                val value = result.get(i)
                if (value !is OnnxTensor) continue
                val info = value.info as ai.onnxruntime.TensorInfo
                val dims = info.shape
                val last = dims.lastOrNull() ?: 0L
                val floats = OrtEngine.readFloats(value)
                when (last) {
                    1L -> strideData += i to floats
                    4L -> bboxData += i to floats
                    10L -> kpsData += i to floats
                }
            }
            // Map tensors to strides. Both known layouts end up correct because the index order of
            // each group matches the stride order (8, 16, 32).
            val strides = intArrayOf(8, 16, 32)
            for (g in 0 until min(fmc, strideData.size)) {
                val stride = strides[g]
                val featureHeight = config.inputHeight / stride
                val featureWidth = config.inputWidth / stride
                val anchorCount = featureHeight * featureWidth * 2 // two anchors per location
                val scores = strideData[g].second
                val boxes = bboxData.getOrNull(g)?.second ?: continue
                val kps = kpsData.getOrNull(g)?.second

                for (a in 0 until anchorCount) {
                    val score = scores.getOrElse(a) { 0f }
                    if (score < config.scoreThreshold) continue
                    val cx = ((a % (featureWidth * 2)) / 2 * stride).toFloat()
                    val cy = ((a / (featureWidth * 2)) * stride).toFloat()
                    // bbox = distance to the anchor, multiplied by the stride.
                    val b0 = boxes.getOrElse(a * 4) { 0f } * stride
                    val b1 = boxes.getOrElse(a * 4 + 1) { 0f } * stride
                    val b2 = boxes.getOrElse(a * 4 + 2) { 0f } * stride
                    val b3 = boxes.getOrElse(a * 4 + 3) { 0f } * stride
                    val x1 = cx - b0
                    val y1 = cy - b1
                    val x2 = cx + b2
                    val y2 = cy + b3

                    // Back to original image coordinates (undo pad + scale).
                    val left = (x1 - padX) / scale
                    val top = (y1 - padY) / scale
                    val right = (x2 - padX) / scale
                    val bottom = (y2 - padY) / scale
                    scoresList += score
                    boxesList += left.toFloat()
                    boxesList += top.toFloat()
                    boxesList += right.toFloat()
                    boxesList += bottom.toFloat()
                    if (kps != null) {
                        for (p in 0 until 5) {
                            val kx = kps.getOrElse(a * 10 + p * 2) { 0f } * stride
                            val ky = kps.getOrElse(a * 10 + p * 2 + 1) { 0f } * stride
                            keypointsList += ((cx + kx - padX) / scale).toFloat()
                            keypointsList += ((cy + ky - padY) / scale).toFloat()
                        }
                    }
                }
            }
        }
        tensor.close()

        // ---- 2. Non maximum suppression ----------------------------------------------------
        val order = scoresList.indices.sortedByDescending { scoresList[it] }
        val keep = mutableListOf<Int>()
        for (i in order) {
            var overlaps = false
            for (j in keep) {
                if (iou(boxesList, i, j) > config.nmsThreshold) {
                    overlaps = true
                    break
                }
            }
            if (!overlaps) keep += i
            if (keep.size >= config.maxFaces) break
        }

        return keep.map { index ->
            val left = boxesList[index * 4].toDouble()
            val top = boxesList[index * 4 + 1].toDouble()
            val right = boxesList[index * 4 + 2].toDouble()
            val bottom = boxesList[index * 4 + 3].toDouble()
            val box = doubleArrayOf(
                left.coerceIn(0.0, imageWidth.toDouble()),
                top.coerceIn(0.0, imageHeight.toDouble()),
                (right - left).coerceAtLeast(1.0),
                (bottom - top).coerceAtLeast(1.0),
            )
            val landmarks = Array(5) { p ->
                Point(
                    keypointsList.getOrElse(index * 10 + p * 2) { (left + right) / 2f }.toDouble(),
                    keypointsList.getOrElse(index * 10 + p * 2 + 1) { (top + bottom) / 2f }.toDouble(),
                )
            }
            DetectedFace(box, scoresList[index], landmarks)
        }.sortedByDescending { it.area }
    }

    /** Intersection over union of two detections in the flat boxes array. */
    private fun iou(boxes: List<Float>, a: Int, b: Int): Float {
        val ax1 = boxes[a * 4]
        val ay1 = boxes[a * 4 + 1]
        val ax2 = boxes[a * 4 + 2]
        val ay2 = boxes[a * 4 + 3]
        val bx1 = boxes[b * 4]
        val by1 = boxes[b * 4 + 1]
        val bx2 = boxes[b * 4 + 2]
        val by2 = boxes[b * 4 + 3]
        val interW = max(0f, min(ax2, bx2) - max(ax1, bx1))
        val interH = max(0f, min(ay2, by2) - max(ay1, by1))
        val inter = interW * interH
        val areaA = max(0f, ax2 - ax1) * max(0f, ay2 - ay1)
        val areaB = max(0f, bx2 - bx1) * max(0f, by2 - by1)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }

    // ---------------------------------------------------------------------------------------
    // Selection helpers
    // ---------------------------------------------------------------------------------------

    /** Largest face (by box area) — the "auto pick" for a selfie or a portrait. */
    fun largestFace(faces: List<DetectedFace>): DetectedFace? = faces.maxByOrNull { it.area }

    /**
     * Sharpest face: highest variance of the Laplacian inside the face box, i.e. the one with the most
     * in-focus detail. Faces smaller than [minimumPx] are ignored because their blur metric is noisy.
     */
    fun sharpestFace(faces: List<DetectedFace>, rgb: Mat, minimumPx: Int = 80): DetectedFace? {
        val candidates = faces.filter { it.widthPx >= minimumPx && it.heightPx >= minimumPx }
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { face ->
            val x = face.box[0].toInt().coerceIn(0, max(0, rgb.cols() - 2))
            val y = face.box[1].toInt().coerceIn(0, max(0, rgb.rows() - 2))
            val w = face.widthPx.toInt().coerceIn(1, rgb.cols() - x)
            val h = face.heightPx.toInt().coerceIn(1, rgb.rows() - y)
            val crop = Mat(rgb, org.opencv.core.Rect(x, y, w, h))
            val gray = Mat()
            Imgproc.cvtColor(crop, gray, Imgproc.COLOR_RGB2GRAY)
            val laplacian = Mat()
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            val mean = org.opencv.core.MatOfDouble()
            val std = org.opencv.core.MatOfDouble()
            org.opencv.core.Core.meanStdDev(laplacian, mean, std)
            gray.release()
            laplacian.release()
            std.toArray()[0]
        }
    }

    /**
     * Picks the face whose centre is closest to a tap position — the "tap to select SOURCE/TARGET"
     * interaction. Returns null when the tap landed further than [maxDistanceFraction] of the image
     * diagonal away from every face.
     */
    fun faceAt(faces: List<DetectedFace>, x: Float, y: Float, width: Int, height: Int, maxDistanceFraction: Float = 0.35f): DetectedFace? {
        if (faces.isEmpty()) return null
        val diagonal = sqrt((width * width + height * height).toDouble())
        var best: DetectedFace? = null
        var bestDistance = Double.MAX_VALUE
        for (face in faces) {
            val distance = sqrt((face.centerX - x) * (face.centerX - x) + (face.centerY - y) * (face.centerY - y))
            if (distance < bestDistance) {
                bestDistance = distance
                best = face
            }
        }
        return if (bestDistance <= diagonal * maxDistanceFraction) best else null
    }

    /**
     * Blurriness estimate (0 = perfectly sharp). Uses the variance of the Laplacian normalised by the
     * face size; the UI shows a warning below [SHARPNESS_WARNING].
     */
    fun sharpnessScore(rgb: Mat, face: DetectedFace): Float {
        val x = face.box[0].toInt().coerceIn(0, max(0, rgb.cols() - 2))
        val y = face.box[1].toInt().coerceIn(0, max(0, rgb.rows() - 2))
        val w = face.widthPx.toInt().coerceIn(1, rgb.cols() - x)
        val h = face.heightPx.toInt().coerceIn(1, rgb.rows() - y)
        val crop = Mat(rgb, org.opencv.core.Rect(x, y, w, h))
        val gray = Mat()
        Imgproc.cvtColor(crop, gray, Imgproc.COLOR_RGB2GRAY)
        val resized = Mat()
        Imgproc.resize(gray, resized, Size(128.0, 128.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val laplacian = Mat()
        Imgproc.Laplacian(resized, laplacian, CvType.CV_64F)
        val mean = org.opencv.core.MatOfDouble()
        val std = org.opencv.core.MatOfDouble()
        org.opencv.core.Core.meanStdDev(laplacian, mean, std)
        val variance = std.toArray()[0] * std.toArray()[0]
        gray.release()
        resized.release()
        laplacian.release()
        return variance.toFloat()
    }

    /** Face crops used by the "which face do you want" thumbnails. */
    fun cropFace(rgb: Mat, face: DetectedFace, margin: Double = 0.25): Mat {
        val w = face.widthPx * (1 + margin * 2)
        val h = face.heightPx * (1 + margin * 2)
        val x = (face.centerX - w / 2).toInt()
        val y = (face.centerY - h / 2).toInt()
        return MatUtils.crop(rgb, x, y, w.toInt(), h.toInt())
    }

    companion object {
        /** Below this variance the face is too soft for a believable swap. */
        const val SHARPNESS_WARNING = 40f

        /** Minimum face width/height in pixels (user requirement). */
        const val MINIMUM_FACE_PX = 80
    }
}
