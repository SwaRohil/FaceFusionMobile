package com.swapstudio.ai.core.mesh

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.swapstudio.ai.core.geometry.FaceGeometry
import com.swapstudio.ai.core.models.ModelManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.opencv.core.Point
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * The 52 ARKit compatible blendshape channels produced by the MediaPipe Face Landmarker.
 *
 * FaceFusion / ARKit order is preserved exactly so coefficients can be logged, smoothed and (later)
 * retargeted without an extra mapping table.
 */
object BlendshapeChannel {
    const val BROW_DOWN_LEFT = "browDownLeft"
    const val BROW_DOWN_RIGHT = "browDownRight"
    const val BROW_INNER_UP = "browInnerUp"
    const val BROW_OUTER_UP_LEFT = "browOuterUpLeft"
    const val BROW_OUTER_UP_RIGHT = "browOuterUpRight"
    const val CHEEK_PUFF = "cheekPuff"
    const val CHEEK_SQUINT_LEFT = "cheekSquintLeft"
    const val CHEEK_SQUINT_RIGHT = "cheekSquintRight"
    const val EYE_BLINK_LEFT = "eyeBlinkLeft"
    const val EYE_BLINK_RIGHT = "eyeBlinkRight"
    const val EYE_LOOK_DOWN_LEFT = "eyeLookDownLeft"
    const val EYE_LOOK_DOWN_RIGHT = "eyeLookDownRight"
    const val EYE_LOOK_IN_LEFT = "eyeLookInLeft"
    const val EYE_LOOK_IN_RIGHT = "eyeLookInRight"
    const val EYE_LOOK_OUT_LEFT = "eyeLookOutLeft"
    const val EYE_LOOK_OUT_RIGHT = "eyeLookOutRight"
    const val EYE_LOOK_UP_LEFT = "eyeLookUpLeft"
    const val EYE_LOOK_UP_RIGHT = "eyeLookUpRight"
    const val EYE_SQUINT_LEFT = "eyeSquintLeft"
    const val EYE_SQUINT_RIGHT = "eyeSquintRight"
    const val EYE_WIDE_LEFT = "eyeWideLeft"
    const val EYE_WIDE_RIGHT = "eyeWideRight"
    const val JAW_FORWARD = "jawForward"
    const val JAW_LEFT = "jawLeft"
    const val JAW_OPEN = "jawOpen"
    const val JAW_RIGHT = "jawRight"
    const val MOUTH_CLOSE = "mouthClose"
    const val MOUTH_DIMPLE_LEFT = "mouthDimpleLeft"
    const val MOUTH_DIMPLE_RIGHT = "mouthDimpleRight"
    const val MOUTH_FROWN_LEFT = "mouthFrownLeft"
    const val MOUTH_FROWN_RIGHT = "mouthFrownRight"
    const val MOUTH_FUNNEL = "mouthFunnel"
    const val MOUTH_LEFT = "mouthLeft"
    const val MOUTH_LOWER_DOWN_LEFT = "mouthLowerDownLeft"
    const val MOUTH_LOWER_DOWN_RIGHT = "mouthLowerDownRight"
    const val MOUTH_PRESS_LEFT = "mouthPressLeft"
    const val MOUTH_PRESS_RIGHT = "mouthPressRight"
    const val MOUTH_PUCKER = "mouthPucker"
    const val MOUTH_RIGHT = "mouthRight"
    const val MOUTH_ROLL_LOWER = "mouthRollLower"
    const val MOUTH_ROLL_UPPER = "mouthRollUpper"
    const val MOUTH_SHRUG_LOWER = "mouthShrugLower"
    const val MOUTH_SHRUG_UPPER = "mouthShrugUpper"
    const val MOUTH_SMILE_LEFT = "mouthSmileLeft"
    const val MOUTH_SMILE_RIGHT = "mouthSmileRight"
    const val MOUTH_STRETCH_LEFT = "mouthStretchLeft"
    const val MOUTH_STRETCH_RIGHT = "mouthStretchRight"
    const val MOUTH_UPPER_UP_LEFT = "mouthUpperUpLeft"
    const val MOUTH_UPPER_UP_RIGHT = "mouthUpperUpRight"
    const val NOSE_SNEER_LEFT = "noseSneerLeft"
    const val NOSE_SNEER_RIGHT = "noseSneerRight"
    const val TONGUE_OUT = "tongueOut"

    /** The canonical 52 channel order (this is what MediaPipe emits). */
    val ALL = listOf(
        BROW_DOWN_LEFT, BROW_DOWN_RIGHT, BROW_INNER_UP, BROW_OUTER_UP_LEFT, BROW_OUTER_UP_RIGHT,
        CHEEK_PUFF, CHEEK_SQUINT_LEFT, CHEEK_SQUINT_RIGHT,
        EYE_BLINK_LEFT, EYE_BLINK_RIGHT,
        EYE_LOOK_DOWN_LEFT, EYE_LOOK_DOWN_RIGHT, EYE_LOOK_IN_LEFT, EYE_LOOK_IN_RIGHT,
        EYE_LOOK_OUT_LEFT, EYE_LOOK_OUT_RIGHT, EYE_LOOK_UP_LEFT, EYE_LOOK_UP_RIGHT,
        EYE_SQUINT_LEFT, EYE_SQUINT_RIGHT, EYE_WIDE_LEFT, EYE_WIDE_RIGHT,
        JAW_FORWARD, JAW_LEFT, JAW_OPEN, JAW_RIGHT,
        MOUTH_CLOSE, MOUTH_DIMPLE_LEFT, MOUTH_DIMPLE_RIGHT, MOUTH_FROWN_LEFT, MOUTH_FROWN_RIGHT,
        MOUTH_FUNNEL, MOUTH_LEFT, MOUTH_LOWER_DOWN_LEFT, MOUTH_LOWER_DOWN_RIGHT,
        MOUTH_PRESS_LEFT, MOUTH_PRESS_RIGHT, MOUTH_PUCKER, MOUTH_RIGHT,
        MOUTH_ROLL_LOWER, MOUTH_ROLL_UPPER, MOUTH_SHRUG_LOWER, MOUTH_SHRUG_UPPER,
        MOUTH_SMILE_LEFT, MOUTH_SMILE_RIGHT, MOUTH_STRETCH_LEFT, MOUTH_STRETCH_RIGHT,
        MOUTH_UPPER_UP_LEFT, MOUTH_UPPER_UP_RIGHT, NOSE_SNEER_LEFT, NOSE_SNEER_RIGHT, TONGUE_OUT,
    )
}

/**
 * A full face analysis for one image or video frame.
 *
 * @param landmarks 478 points in pixels (`x`, `y`; `z` kept separately in [depth])
 * @param normalized the raw 0..1 landmarks, needed by MediaPipe's helper APIs
 * @param blendshapes 52 coefficients in [BlendshapeChannel.ALL] order, 0..1
 * @param transformation 4x4 column major facial transformation matrix (pose), if available
 */
data class MeshFace(
    val landmarks: Array<Point>,
    val normalized: FloatArray,
    val depth: FloatArray,
    val blendshapes: FloatArray,
    val transformation: FloatArray?,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    /** Bounding box of the mesh silhouette. */
    val bounds: DoubleArray get() = FaceGeometry.boundsFromMesh(landmarks)

    val yaw: Float get() = FaceGeometry.yawPitchDegrees(landmarks).first
    val pitch: Float get() = FaceGeometry.yawPitchDegrees(landmarks).second
    val roll: Float get() = FaceGeometry.rollDegrees(landmarks)

    /** Blendshape lookup by name. */
    fun blendshape(name: String): Float {
        val index = BlendshapeChannel.ALL.indexOf(name)
        return if (index in blendshapes.indices) blendshapes[index] else 0f
    }

    /** Convenience: eye blink (average of both lids) used for eye refinement strength. */
    val blink: Float get() = (blendshape(BlendshapeChannel.EYE_BLINK_LEFT) + blendshape(BlendshapeChannel.EYE_BLINK_RIGHT)) / 2f

    /** Convenience: mouth opening, used to keep teeth and inner mouth stable. */
    val mouthOpen: Float get() = blendshape(BlendshapeChannel.JAW_OPEN)
}

/**
 * MediaPipe Face Landmarker wrapper.
 *
 * Runs the `face_landmarker.task` bundle in VIDEO mode so tracking is reused between frames (that is
 * what keeps the mesh stable during a video swap), and returns 478 landmarks + 52 blendshapes + the
 * facial transformation matrix.
 *
 * The landmarker is expensive to create, so one instance is cached per process and recreated only when
 * the delegate changes (GPU <-> CPU) or the model file is re-downloaded.
 */
@Singleton
class FaceMeshAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: ModelManager,
) {
    private var landmarker: FaceLandmarker? = null
    private var landmarkerDelegate: Delegate? = null
    private var landmarkerTimestamp: Long = 0L

    /**
     * Creates (or reuses) the landmarker.
     *
     * @param useGpu true for the GPU delegate (fast enough for 60 fps, needs OpenGL ES 3.0+);
     *   false for CPU. The GPU delegate is tried first by the "Max" performance mode and falls back
     *   to CPU automatically when the device cannot create it.
     */
    @Synchronized
    fun ensureLandmarker(useGpu: Boolean): FaceLandmarker {
        val file = models.faceLandmarkerFile()
        require(file.exists()) { "Face landmarker model missing - open the Model Manager" }
        val desired = if (useGpu) Delegate.GPU else Delegate.CPU
        landmarker?.let { existing ->
            if (landmarkerDelegate == desired) return existing
            existing.close()
            landmarker = null
        }
        val created = createLandmarker(file, desired)
        landmarker = created
        landmarkerDelegate = desired
        landmarkerTimestamp = 0L
        return created
    }

    private fun createLandmarker(file: java.io.File, delegate: Delegate): FaceLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(file.absolutePath)
            .setDelegate(delegate)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(MAX_FACES)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .build()
        return try {
            FaceLandmarker.createFromOptions(context, options)
        } catch (t: Throwable) {
            if (delegate == Delegate.GPU) {
                Log.w(TAG, "GPU delegate unavailable, falling back to CPU", t)
                createLandmarker(file, Delegate.CPU)
            } else {
                throw t
            }
        }
    }

    /**
     * Analyses one frame in video mode.
     *
     * @param bitmap frame (will be copied by MediaPipe; safe to recycle afterwards)
     * @param timestampMs monotonically increasing timestamp in milliseconds
     */
    fun analyzeVideoFrame(bitmap: Bitmap, timestampMs: Long, useGpu: Boolean = true): List<MeshFace> {
        val lm = ensureLandmarker(useGpu)
        val safeTimestamp = if (timestampMs <= landmarkerTimestamp) landmarkerTimestamp + 1 else timestampMs
        landmarkerTimestamp = safeTimestamp
        val image = BitmapImageBuilder(bitmap).build()
        val result = lm.detectForVideo(image, safeTimestamp)
        return convert(result, bitmap.width, bitmap.height)
    }

    /** Analyses a still image (IMAGE mode semantics emulated through video mode + unique timestamp). */
    fun analyzeImage(bitmap: Bitmap, useGpu: Boolean = false): List<MeshFace> {
        val lm = ensureLandmarker(useGpu)
        // Video mode keeps tracking between calls which is fine for stills when we force a new
        // timestamp: MediaPipe then simply re-detects instead of relying on the previous frame.
        landmarkerTimestamp += 1000
        val image = BitmapImageBuilder(bitmap).build()
        val result = lm.detectForVideo(image, landmarkerTimestamp)
        return convert(result, bitmap.width, bitmap.height)
    }

    /** Resets the tracker, e.g. between two different videos so no stale pose leaks through. */
    @Synchronized
    fun resetTracker() {
        landmarker?.close()
        landmarker = null
    }

    /** Releases the native landmarker. */
    @Synchronized
    fun close() {
        landmarker?.close()
        landmarker = null
        landmarkerDelegate = null
    }

    private fun convert(result: com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult, width: Int, height: Int): List<MeshFace> {
        val faces = ArrayList<MeshFace>(result.faceLandmarks().size)
        val blendshapes = result.faceBlendshapes().orElse(null)
        val matrices = result.facialTransformationMatrixes().orElse(null)
        result.faceLandmarks().forEachIndexed { index, list ->
            val count = list.size
            val normalized = FloatArray(count * 3)
            val depth = FloatArray(count)
            val points = Array(count) { Point(0.0, 0.0) }
            for (i in 0 until count) {
                val landmark = list[i]
                normalized[i * 3] = landmark.x()
                normalized[i * 3 + 1] = landmark.y()
                normalized[i * 3 + 2] = landmark.z()
                depth[i] = landmark.z()
                points[i] = Point(landmark.x().toDouble() * width, landmark.y().toDouble() * height)
            }
            val coefficients = FloatArray(BlendshapeChannel.ALL.size)
            val categories = blendshapes?.getOrNull(index)
            if (categories != null) {
                for (category in categories) {
                    val slot = BlendshapeChannel.ALL.indexOf(category.categoryName())
                    if (slot >= 0) coefficients[slot] = category.score()
                }
            }
            faces += MeshFace(
                landmarks = points,
                normalized = normalized,
                depth = depth,
                blendshapes = coefficients,
                transformation = matrices?.getOrNull(index),
                imageWidth = width,
                imageHeight = height,
            )
        }
        return faces
    }

    /**
     * Matches a detector box to the mesh that covers it best (largest landmark overlap). Returns the
     * index into [faces] or -1.
     */
    fun matchMesh(faces: List<MeshFace>, box: DoubleArray): Int {
        var bestIndex = -1
        var bestOverlap = 0.0
        faces.forEachIndexed { index, mesh ->
            var inside = 0
            for (point in mesh.landmarks) {
                if (isInside(point, box)) inside++
            }
            val overlap = inside.toDouble() / maxOf(1, mesh.landmarks.size)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestIndex = index
            }
        }
        return if (bestOverlap > 0.5) bestIndex else -1
    }

    private fun isInside(point: Point, box: DoubleArray): Boolean =
        point.x >= box[0] && point.x <= box[0] + box[2] && point.y >= box[1] && point.y <= box[1] + box[3]

    /**
     * Head pose from the transformation matrix when available, else from the mesh geometry.
     * Returns yaw/pitch/roll in degrees.
     */
    fun headPose(face: MeshFace): Triple<Float, Float, Float> {
        val matrix = face.transformation
        if (matrix != null && matrix.size >= 16) {
            // Column major 4x4; the rotation part encodes the head orientation.
            val r00 = matrix[0]; val r01 = matrix[4]; val r02 = matrix[8]
            val r10 = matrix[1]; val r12 = matrix[9]
            val yaw = Math.toDegrees(kotlin.math.atan2(-r02.toDouble(), kotlin.math.sqrt((r00 * r00 + r01 * r01).toDouble()))).toFloat()
            val pitch = Math.toDegrees(kotlin.math.atan2(r12.toDouble(), kotlin.math.sqrt((r10 * r10 + r12 * r12).toDouble()))).toFloat()
            val roll = Math.toDegrees(kotlin.math.atan2(r01.toDouble(), r00.toDouble())).toFloat()
            return Triple(yaw, pitch, roll)
        }
        val (yaw, pitch) = FaceGeometry.yawPitchDegrees(face.landmarks)
        return Triple(yaw, pitch, FaceGeometry.rollDegrees(face.landmarks))
    }

    companion object {
        private const val TAG = "FaceMeshAnalyzer"

        /** Upper bound; MediaPipe allows more but each face costs inference time. */
        const val MAX_FACES = 5

        /** MediaPipe mesh point count including the iris points. */
        const val LANDMARK_COUNT = FaceGeometry.MESH_POINT_COUNT

        /** True when the mesh contains the 10 iris points (they appear with refine_landmarks). */
        fun hasIris(face: MeshFace): Boolean = face.landmarks.size >= 478

        /** Gaze vector estimate from the iris position inside each eye. */
        fun gaze(face: MeshFace): Pair<Float, Float> {
            if (!hasIris(face)) return 0f to 0f
            val rightEye = face.landmarks[FaceGeometry.RIGHT_IRIS_CENTER]
            val rightOuter = face.landmarks[FaceGeometry.RIGHT_EYE_OUTER]
            val rightInner = face.landmarks[FaceGeometry.RIGHT_EYE_INNER]
            val horizontal = ((rightEye.x - rightOuter.x) / (rightInner.x - rightOuter.x + 1e-6)).toFloat()
            val vertical = (rightEye.y - (rightOuter.y + rightInner.y) / 2.0).toFloat() / 20f
            return horizontal.coerceIn(-1f, 2f) - 0.5f to vertical.coerceIn(-1f, 1f)
        }

        /** Rough symmetry check: a strongly asymmetric mesh usually means an occlusion. */
        fun symmetry(face: MeshFace): Float {
            val (rightEye, leftEye) = FaceGeometry.eyeCentres(face.landmarks)
            val axis = (rightEye.x + leftEye.x) / 2.0
            var total = 0.0
            var count = 0
            for (pair in SYMMETRY_PAIRS) {
                if (pair[0] >= face.landmarks.size || pair[1] >= face.landmarks.size) continue
                val a = face.landmarks[pair[0]]
                val b = face.landmarks[pair[1]]
                total += abs(axis - a.x) - abs(b.x - axis)
                count++
            }
            return if (count == 0) 0f else (total / count).toFloat()
        }

        /** Left/right landmark index pairs used by [symmetry]. */
        private val SYMMETRY_PAIRS = listOf(
            intArrayOf(33, 263), intArrayOf(133, 362), intArrayOf(61, 291), intArrayOf(70, 300),
            intArrayOf(234, 454), intArrayOf(132, 361), intArrayOf(58, 288), intArrayOf(172, 397),
        )
    }
}
