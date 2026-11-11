package com.swapstudio.ai.core.swap

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.util.Log
import com.swapstudio.ai.core.geometry.FaceWarp
import com.swapstudio.ai.core.geometry.FaceGeometry
import com.swapstudio.ai.core.inference.OrtEngine
import com.swapstudio.ai.core.inference.SessionSpec
import com.swapstudio.ai.core.models.ModelManager
import com.swapstudio.ai.core.util.MatUtils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Result of swapping one face into one frame.
 *
 * @param rgb the composited frame (`CV_8UC3`, RGB), owned by the caller
 * @param identityScore cosine similarity between the source identity and the swapped face, 0..1
 * @param crop the raw 128x128 swapped crop, useful for debugging and for the "eye/teeth refine" pass
 * @param affine the alignment matrix that was used (crop -> frame)
 */
data class SwapResult(
    val rgb: Mat,
    val identityScore: Float,
    val crop: Mat,
    val affine: Mat,
)

/**
 * The face swap itself.
 *
 * Pipeline per face (this mirrors what FaceFusion does, re-implemented for ONNX Runtime on Android):
 *
 * 1. **Align** the target face to a 128x128 crop with the five point ArcFace template
 *    (`arcface_128_v2`) using a similarity transform.
 * 2. **Embed** the source face with ArcFace R50 (112x112, `arcface_112_v2` template) and normalise.
 * 3. **Transform the embedding** with the `inswapper_128` initializer matrix that lives in the last
 *    initializer of the ONNX graph: `latent = normalize(dot(embedding, matrix))`. This is the trick
 *    that lets a 512-d ArcFace vector drive the swapper.
 * 4. **Run** `inswapper_128_fp16` with `source` = latent `[1,512]` and `target` = crop `[1,3,128,128]`
 *    (RGB, 0..1).
 * 5. **Restore** the crop with GFPGAN (optional, controlled by the Hyper Realistic slider).
 * 6. **Colour match and paste back** with a feathered, occlusion-aware mask.
 */
@Singleton
class FaceSwapper @Inject constructor(
    private val models: ModelManager,
) {
    @Volatile
    private var latentMatrix: Array<FloatArray>? = null
    @Volatile
    private var latentMatrixSource: String? = null

    /**
     * One swapper session plus the ArcFace session, kept alive between frames.
     * Created through [createPipeline] and released with [SwapPipeline.close].
     */
    inner class SwapPipeline(spec: SessionSpec) : AutoCloseable {
        val swapper: OrtSession = models.sessionFor(models.swapperFile(), spec)
        val arcface: OrtSession = models.sessionFor(models.arcFaceFile(), spec)
        val swapperInput: String = swapper.inputNames.first()
        val swapperSourceInput: String = swapper.inputNames.firstOrNull { it.contains("source", true) } ?: "source"
        val swapperTargetInput: String = swapper.inputNames.firstOrNull { it.contains("target", true) } ?: "target"
        val arcfaceInput: String = arcface.inputNames.first()

        /** Cached source latent: computed once per source photo, not per frame. */
        var sourceLatent: FloatArray? = null
        var sourceEmbedding: FloatArray? = null

        override fun close() {
            // Sessions are owned by ModelManager's cache; only drop our references.
            sourceLatent = null
            sourceEmbedding = null
        }
    }

    /** Builds a reusable pipeline for a batch of frames. */
    fun createPipeline(spec: SessionSpec): SwapPipeline = SwapPipeline(spec)

    // -----------------------------------------------------------------------------------------
    // Source embedding
    // -----------------------------------------------------------------------------------------

    /**
     * Computes the normalised ArcFace embedding of the *source* face.
     *
     * @param rgb source image (`CV_8UC3`, RGB)
     * @param landmarks five source landmarks in pixels
     */
    fun embedSource(pipeline: SwapPipeline, rgb: Mat, landmarks: MatOfPoint2f): FloatArray {
        val affine = FaceWarp.estimateAffine(landmarks, FaceWarp.ARCFACE_112_V2, 112)
        val crop = FaceWarp.warp(rgb, affine, 112)
        affine.release()
        try {
            val input = MatUtils.toNchwFloat(
                crop,
                mean = floatArrayOf(127.5f, 127.5f, 127.5f),
                std = floatArrayOf(1f / 128f, 1f / 128f, 1f / 128f),
                scale = 1f,
                bgr = true,
            )
            val tensor = OnnxTensor.createTensor(
                OrtEngine.env(), FloatBuffer.wrap(input), longArrayOf(1, 3, 112, 112),
            )
            val output = pipeline.arcface.run(mapOf(pipeline.arcfaceInput to tensor)).use { result ->
                val value = result.get(0)
                check(value is OnnxTensor) { "ArcFace output is not a tensor" }
                OrtEngine.readFloats(value)
            }
            tensor.close()
            return l2Normalise(output)
        } finally {
            crop.release()
        }
    }

    /**
     * Converts the 512-d ArcFace embedding into the 512-d latent the swapper expects.
     *
     * `inswapper_128.onnx` stores its projection matrix as the last initializer of the graph. It is a
     * `[512, 512]` float matrix. Because ONNX Runtime does not expose initializers, the matrix is
     * extracted offline once (`tools/extract_inswapper_matrix.py`) and shipped as
     * `assets/inswapper_latent.bin` (512*512 float32, row major). When the file is missing we fall
     * back to the identity projection, which still swaps but with lower identity fidelity.
     */
    fun toLatent(embedding: FloatArray): FloatArray {
        val matrix = loadLatentMatrix()
        if (matrix == null) return l2Normalise(embedding)
        val size = embedding.size
        val out = FloatArray(size)
        for (row in 0 until size) {
            var sum = 0f
            val m = matrix[row]
            for (col in 0 until size) sum += embedding[col] * m[col]
            out[row] = sum
        }
        // FaceFusion divides by the *source* norm, not by the projection norm.
        var norm = 0.0
        for (v in embedding) norm += v.toDouble() * v
        norm = sqrt(norm).coerceAtLeast(1e-6)
        for (i in out.indices) out[i] = (out[i] / norm).toFloat()
        return out
    }

    private fun loadLatentMatrix(): Array<FloatArray>? {
        val file = models.store.resolve("inswapper_latent.bin")
        if (!file.exists()) return null
        val source = file.absolutePath
        latentMatrix?.let { if (latentMatrixSource == source) return it }
        return try {
            val bytes = file.readBytes()
            val floats = FloatArray(bytes.size / 4)
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            val side = sqrt(floats.size.toFloat()).toInt()
            val matrix = Array(side) { row -> FloatArray(side) { col -> floats[row * side + col] } }
            latentMatrix = matrix
            latentMatrixSource = source
            matrix
        } catch (t: Throwable) {
            Log.w(TAG, "cannot read inswapper latent matrix", t)
            null
        }
    }

    // -----------------------------------------------------------------------------------------
    // Swapping
    // -----------------------------------------------------------------------------------------

    /**
     * Swaps the source identity into the face described by [targetLandmarks].
     *
     * @param pipeline reusable sessions + cached source latent
     * @param frame RGB `CV_8UC3`
     * @param targetLandmarks five points of the target face in [frame] pixels
     * @param mask optional occlusion mask in frame space (`CV_32FC1` 0..1 or `CV_8UC1`); the swap is
     *   multiplied by it, so glasses/hair/hands stay untouched
     * @param enhancer optional hyper realistic refinement, applied to the 128 crop before paste back
     * @return [SwapResult] with an owned copy of the frame; [frame] itself is not modified
     */
    fun swap(
        pipeline: SwapPipeline,
        frame: Mat,
        targetLandmarks: MatOfPoint2f,
        mask: Mat? = null,
        enhancer: HyperRealEnhancer? = null,
        enhancerFidelity: Float = 0.65f,
        pasteStrength: Float = 1f,
    ): SwapResult {
        val latent = pipeline.sourceLatent ?: error("call prepareSource() first")

        // 1. Align ---------------------------------------------------------------
        val affine = FaceWarp.estimateAffine(targetLandmarks, FaceWarp.ARCFACE_128_V2, 128)
        val crop = FaceWarp.warp(frame, affine, 128)
        var swapped: Mat = crop
        try {
            // 2. Run the swapper --------------------------------------------------
            val cropInput = MatUtils.toNchwFloat(
                crop,
                mean = floatArrayOf(0f, 0f, 0f),
                std = floatArrayOf(1f, 1f, 1f),
                scale = 1f / 255f,
                bgr = true,
            )
            val cropTensor = OnnxTensor.createTensor(
                OrtEngine.env(), FloatBuffer.wrap(cropInput), longArrayOf(1, 3, 128, 128),
            )
            val sourceTensor = OnnxTensor.createTensor(
                OrtEngine.env(), FloatBuffer.wrap(latent), longArrayOf(1, latent.size.toLong()),
            )
            val outputs = pipeline.swapper.run(
                mapOf(
                    pipeline.swapperSourceInput to sourceTensor,
                    pipeline.swapperTargetInput to cropTensor,
                ),
            ).use { result ->
                val value = result.get(0)
                check(value is OnnxTensor) { "inswapper output is not a tensor" }
                OrtEngine.readFloats(value)
            }
            cropTensor.close()
            sourceTensor.close()

            val swappedCrop = MatUtils.nchwFloatToRgb(outputs, 128, 128, bgr = true)
            crop.release()
            swapped = swappedCrop

            // 4. Build the paste mask ---------------------------------------------
            val boxMask = FaceWarp.boxMask(128, border = 0.06) // CV_32FC1
            val cropMask = if (mask != null) {
                val inverse = Mat()
                Imgproc.invertAffineTransform(affine, inverse)
                val frameBack = Mat()
                Imgproc.warpAffine(mask, frameBack, affine, Size(128.0, 128.0), Imgproc.INTER_LINEAR)
                inverse.release()
                val normalised = Mat()
                when (frameBack.type()) {
                    CvType.CV_8UC1 -> frameBack.convertTo(normalised, CvType.CV_32FC1, 1.0 / 255.0)
                    CvType.CV_32FC1 -> frameBack.copyTo(normalised)
                    else -> frameBack.convertTo(normalised, CvType.CV_32FC1)
                }
                frameBack.release()
                val combined = Mat()
                org.opencv.core.Core.multiply(boxMask, normalised, combined)
                boxMask.release()
                normalised.release()
                combined
            } else {
                boxMask
            }

            // pasteStrength scales the whole mask (0 = untouched target, 1 = full swap).
            if (pasteStrength < 1f) {
                org.opencv.core.Core.multiply(cropMask, org.opencv.core.Scalar.all(pasteStrength.toDouble()), cropMask)
            }

            // 5. Paste back --------------------------------------------------------
            val output = frame.clone()
            FaceWarp.pasteBack(output, swapped, cropMask, affine)
            cropMask.release()

            // 6. Hyper realistic refinement (GFPGAN with a fidelity weight). It runs on the
            //    composited frame so pores are restored *after* the colour/paste step, which is what
            //    keeps the skin from looking plastic where the two faces meet.
            if (enhancer != null && enhancer.isAvailable()) {
                val ffhq = FaceWarp.estimateAffine(targetLandmarks, FaceWarp.FFHQ_512, 512)
                enhancer.enhanceFrame(output, ffhq, enhancerFidelity, mask)
                ffhq.release()
            }

            // 7. Identity score ----------------------------------------------------
            val identity = measureIdentity(pipeline, output, targetLandmarks)

            return SwapResult(rgb = output, identityScore = identity, crop = swapped.clone(), affine = affine)
        } catch (t: Throwable) {
            crop.release()
            affine.release()
            throw t
        }
    }

    /**
     * Caches the source latent so a video only pays the ArcFace cost once.
     * Returns the embedding (kept for the identity score).
     */
    fun prepareSource(pipeline: SwapPipeline, sourceRgb: Mat, sourceLandmarks: MatOfPoint2f): FloatArray {
        val embedding = embedSource(pipeline, sourceRgb, sourceLandmarks)
        pipeline.sourceEmbedding = embedding
        pipeline.sourceLatent = toLatent(embedding)
        return embedding
    }

    /**
     * ArcFace cosine similarity between the swapped result and the source identity.
     * Values above 0.85 mean "same person" for InsightFace models.
     */
    fun measureIdentity(pipeline: SwapPipeline, result: Mat, targetLandmarks: MatOfPoint2f): Float {
        val source = pipeline.sourceEmbedding ?: return 0f
        val swappedLandmarks = estimateSwappedLandmarks(result, targetLandmarks) ?: return 0f
        return try {
            val embedding = embedSource(pipeline, result, swappedLandmarks)
            var dot = 0f
            for (i in source.indices) dot += source[i] * embedding[i]
            dot.coerceIn(-1f, 1f)
        } catch (t: Throwable) {
            0f
        } finally {
            swappedLandmarks.release()
        }
    }

    /**
     * The swapper keeps the target pose, so the same five points still describe the swapped face.
     * Returned as an owned [MatOfPoint2f].
     */
    private fun estimateSwappedLandmarks(frame: Mat, landmarks: MatOfPoint2f): MatOfPoint2f? {
        if (landmarks.total() < 5) return null
        // `toArray()` is platform-typed (Point[]!), so pass an explicit non-null array.
        val points: Array<Point> = landmarks.toArray()
        return MatOfPoint2f(*points)
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /** L2 normalisation of an embedding in place (returns the same array for chaining). */
    fun l2Normalise(values: FloatArray): FloatArray {
        var norm = 0.0
        for (v in values) norm += v.toDouble() * v
        norm = sqrt(norm)
        if (norm > 1e-6) for (i in values.indices) values[i] = (values[i] / norm).toFloat()
        return values
    }

    /** Builds the five point mat for one mesh (order: right eye, left eye, nose, mouth right/left). */
    fun landmarksFromMesh(mesh: com.swapstudio.ai.core.mesh.MeshFace): MatOfPoint2f =
        FaceGeometry.fivePoint(mesh.landmarks)

    /** Builds the five point mat from a detector result. */
    fun landmarksFromDetection(face: com.swapstudio.ai.core.detection.DetectedFace): MatOfPoint2f {
        // The Kotlin view of `Point[]` is `Array<Point!>?`; naming the target type keeps the spread
        // operator happy without a null assertion.
        val points: Array<Point> = face.landmarks
        return MatOfPoint2f(*points)
    }

    /** Five point mat from a mesh. */
    fun landmarksFromMeshPoints(points: Array<Point>): MatOfPoint2f = FaceGeometry.fivePoint(points)

    /** Convenience overload using plain points. */
    fun landmarksOf(points: List<Point>): MatOfPoint2f = MatOfPoint2f(*points.toTypedArray<Point>())

    /** Simple 5-point template mat for a target face at [size] (exposed for tests). */
    fun templatePoints(template: DoubleArray, size: Int): PointArray = PointArray(
        Array(5) { i -> Point(template[i * 2] * size, template[i * 2 + 1] * size) },
    )

    /** Tiny value wrapper so templates can be unit tested without OpenCV mats. */
    data class PointArray(val points: Array<Point>)

    private companion object {
        const val TAG = "FaceSwapper"
    }
}
