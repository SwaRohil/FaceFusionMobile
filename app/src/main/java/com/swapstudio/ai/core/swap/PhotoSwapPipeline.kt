package com.swapstudio.ai.core.swap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.swapstudio.ai.core.detection.DetectedFace
import com.swapstudio.ai.core.detection.FaceDetector
import com.swapstudio.ai.core.geometry.FaceGeometry
import com.swapstudio.ai.core.geometry.FaceWarp
import com.swapstudio.ai.core.inference.EpKind
import com.swapstudio.ai.core.inference.SessionSpec
import com.swapstudio.ai.core.mesh.FaceMeshAnalyzer
import com.swapstudio.ai.core.mesh.MeshFace
import com.swapstudio.ai.core.models.ModelManager
import com.swapstudio.ai.core.processing.ColorMatcher
import com.swapstudio.ai.core.util.BitmapUtils
import com.swapstudio.ai.core.util.MatUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Everything the UI needs to know about one photo before the user presses "Swap".
 *
 * @param sourceFaces faces found in the source image (the identity donors)
 * @param targetFaces faces found in the target image (the bodies/scenes)
 * @param sourceIndex currently selected source face
 * @param targetIndex currently selected target face
 */
data class PhotoAnalysis(
    val sourceFaces: List<DetectedFace>,
    val targetFaces: List<DetectedFace>,
    val sourceMesh: List<MeshFace>,
    val targetMesh: List<MeshFace>,
    val sourceIndex: Int,
    val targetIndex: Int,
    val sourceSharpness: Float,
    val targetSharpness: Float,
    val warnings: List<String>,
)

/**
 * Configuration for one photo swap, straight from the UI.
 *
 * @param hyperRealistic the big toggle; when off the GFPGAN detail pass is skipped
 * @param enhancerStrength slider 0..100 -> GFPGAN fidelity weight
 * @param grainMatch copy the target's film grain onto the swapped face (independent of the detail
 *   pass, so the texture can be kept while the enhancer is off)
 * @param keepTargetExpression blendshape transfer is not applicable to stills; kept for symmetry
 * @param upscaleTo4k runs Real-ESRGAN tiling up to 4K before saving
 * @param exportPng true = PNG (lossless), false = JPEG quality 100
 */
data class PhotoSwapSettings(
    val hyperRealistic: Boolean = true,
    val enhancerStrength: Int = 70,
    val grainMatch: Boolean = true,
    val keepTargetExpression: Boolean = true,
    val upscaleTo4k: Boolean = false,
    val exportPng: Boolean = false,
    val showMesh: Boolean = false,
    val performanceMode: PerformanceMode = PerformanceMode.MAX,
)

/** Colours offered for the mesh overlay (ARGB, as stored in [com.swapstudio.ai.data.AppSettings]). */
object MeshOverlayPalette {
    /** Mint: the app accent. */
    const val MINT = 0xFF00E5C0.toInt()

    /** Hot pink: stands out on skin tones. */
    const val PINK = 0xFFFF3D71.toInt()

    /** Amber: visible on dark footage. */
    const val AMBER = 0xFFFFB020.toInt()

    /** White: the neutral choice for screenshots. */
    const val WHITE = 0xFFF5F7FA.toInt()

    /** Every selectable colour, in the order the settings chips show them. */
    val ALL: List<Int> = listOf(MINT, PINK, AMBER, WHITE)

    /** Human readable name, used by the settings chips and the diagnostics row. */
    fun name(argb: Int): String = when (argb) {
        MINT -> "Mint"
        PINK -> "Pink"
        AMBER -> "Amber"
        WHITE -> "White"
        else -> "#%08X".format(argb)
    }
}

/** Default mesh accent (kept as a top level constant so the default parameter is a compile time value). */
const val MESH_ACCENT_MINT: Int = 0xFF00E5C0.toInt()

/** Hardware mode selected on the home screen / settings. */
enum class PerformanceMode {
    /** CPU only, few threads: cool phone, long battery life. */
    BATTERY_SAVER,

    /** CPU + NNAPI (which may pick the GPU or a low power NPU path). */
    BALANCED,

    /** QNN HTP (Hexagon NPU) with fp16, NNAPI/GPU for the MediaPipe mesh. */
    MAX,
}

/** Result of a finished photo swap. */
data class PhotoSwapResult(
    val bitmap: Bitmap,
    val identityScore: Float,
    val width: Int,
    val height: Int,
    val elapsedMs: Long,
    val warnings: List<String>,
)

/**
 * Photo face swap pipeline.
 *
 * Loading -> EXIF orientation -> detection (source + target) -> mesh (478 points) -> source embedding
 * -> swap -> occlusion parse -> colour match -> GFPGAN -> paste -> grain -> eye refine -> optional 4K
 * upscale -> bitmap.
 *
 * Every stage is exposed as a suspend function on `Dispatchers.Default` so the ViewModel can show a
 * progress bar between the steps.
 */
@Singleton
class PhotoSwapPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: ModelManager,
    private val detector: FaceDetector,
    private val meshAnalyzer: FaceMeshAnalyzer,
    private val swapper: FaceSwapper,
    private val blender: FaceBlender,
    private val enhancer: HyperRealEnhancer,
) {

    /** Reads a bitmap from a content Uri, honouring the EXIF rotation. */
    suspend fun loadBitmap(uri: Uri, maxDimension: Int = 4096): Bitmap = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Cannot decode image")
        val rotation = context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) 0 else {
                val exif = ExifInterface(input)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        }
        if (rotation == 0) decoded else rotate(decoded, rotation)
    }

    /** Rotates a bitmap, recycling nothing (the caller owns both). */
    fun rotate(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (max(w, h) / sample > maxDimension) sample *= 2
        return sample
    }

    /**
     * Detects faces + mesh in both images and picks sensible defaults (largest + sharpest).
     */
    suspend fun analyse(source: Bitmap, target: Bitmap): PhotoAnalysis = withContext(Dispatchers.Default) {
        val warnings = mutableListOf<String>()
        val sourceRgb = BitmapUtils.bitmapToRgb(source)
        val targetRgb = BitmapUtils.bitmapToRgb(target)
        try {
            val sourceFaces = detector.detect(sourceRgb)
            val targetFaces = detector.detect(targetRgb)
            if (sourceFaces.isEmpty()) warnings += "No face found in the source photo. Use a clear, front-facing portrait."
            if (targetFaces.isEmpty()) warnings += "No face found in the target photo."

            val sourceMesh = meshAnalyzer.analyzeImage(source)
            val targetMesh = meshAnalyzer.analyzeImage(target)
            if (sourceMesh.isNotEmpty() && sourceMesh[0].landmarks.size != FaceGeometry.MESH_POINT_COUNT) {
                warnings += "Face mesh returned ${sourceMesh[0].landmarks.size} points instead of 478."
            }

            val sourceSharp = sourceFaces.maxOfOrNull { detector.sharpnessScore(sourceRgb, it) } ?: 0f
            val targetSharp = targetFaces.maxOfOrNull { detector.sharpnessScore(targetRgb, it) } ?: 0f
            if (sourceSharp < FaceDetector.SHARPNESS_WARNING) {
                warnings += "The source face is soft (sharpness ${sourceSharp.toInt()}). For a hyper realistic result use a sharper, well lit photo."
            }
            val smallFaces = (sourceFaces + targetFaces).filter { it.widthPx < FaceDetector.MINIMUM_FACE_PX || it.heightPx < FaceDetector.MINIMUM_FACE_PX }
            if (smallFaces.isNotEmpty()) {
                warnings += "${smallFaces.size} face(s) are smaller than ${FaceDetector.MINIMUM_FACE_PX} px and will look blurry. Use a clear photo."
            }
            val largestSource = sourceFaces.maxByOrNull { it.area }
            val largestTarget = targetFaces.maxByOrNull { it.area }
            PhotoAnalysis(
                sourceFaces = sourceFaces,
                targetFaces = targetFaces,
                sourceMesh = sourceMesh,
                targetMesh = targetMesh,
                sourceIndex = sourceFaces.indexOf(largestSource).coerceAtLeast(0),
                targetIndex = targetFaces.indexOf(largestTarget).coerceAtLeast(0),
                sourceSharpness = sourceSharp,
                targetSharpness = targetSharp,
                warnings = warnings,
            )
        } finally {
            sourceRgb.release()
            targetRgb.release()
        }
    }

    /**
     * Runs the full photo swap.
     *
     * @param sourceIndex which face of [source], defaults to the analysis suggestion
     * @param targetIndex which face of [target]
     */
    suspend fun swap(
        source: Bitmap,
        target: Bitmap,
        settings: PhotoSwapSettings,
        sourceIndex: Int? = null,
        targetIndex: Int? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): PhotoSwapResult = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        val warnings = mutableListOf<String>()
        val spec = sessionSpec(settings.performanceMode)

        val sourceRgb = BitmapUtils.bitmapToRgb(source)
        val targetRgb = BitmapUtils.bitmapToRgb(target)
        var output: Mat? = null
        try {
            onProgress(0.05f, "Detecting faces")
            val sourceFaces = detector.detect(sourceRgb)
            val targetFaces = detector.detect(targetRgb)
            require(sourceFaces.isNotEmpty()) { "No face in the source photo" }
            require(targetFaces.isNotEmpty()) { "No face in the target photo" }

            val sIdx = (sourceIndex ?: 0).coerceIn(0, sourceFaces.size - 1)
            val tIdx = (targetIndex ?: 0).coerceIn(0, targetFaces.size - 1)
            val sourceFace = sourceFaces[sIdx]
            val targetFace = targetFaces[tIdx]

            onProgress(0.12f, "Building the 478 point mesh")
            val sourceMeshes = meshAnalyzer.analyzeImage(source)
            val targetMeshes = meshAnalyzer.analyzeImage(target)
            val sourceMesh = pickMesh(sourceMeshes, sourceFace) ?: error("Mesh failed on the source face")
            val targetMesh = pickMesh(targetMeshes, targetFace) ?: error("Mesh failed on the target face")

            onProgress(0.22f, "Reading the source identity")
            val pipeline = swapper.createPipeline(spec)
            try {
                val sourceLandmarks = FaceGeometry.fivePoint(sourceMesh.landmarks)
                swapper.prepareSource(pipeline, sourceRgb, sourceLandmarks)
                sourceLandmarks.release()

                onProgress(0.32f, "Swapping the face")
                val targetLandmarks = FaceGeometry.fivePoint(targetMesh.landmarks)

                // Occlusion pass: a 512x512 BGR frame crop, exactly what the BiSeNet 512 model wants.
                // Its result is mapped into frame space and used both for the paste mask and for the
                // skin-only colour mask.
                val frame512 = MatUtils.resize(targetRgb, 512, 512)
                val parse = blender.parseOcclusion(frame512)
                frame512.release()
                val occlusionFrame = parse?.let {
                    val upscaled = MatUtils.resize(it.occlusionMask, targetRgb.cols(), targetRgb.rows())
                    it.occlusionMask.release()
                    upscaled
                }

                val swap = swapper.swap(
                    pipeline = pipeline,
                    frame = targetRgb,
                    targetLandmarks = targetLandmarks,
                    mask = occlusionFrame,
                    enhancer = null,
                    pasteStrength = 1f,
                )
                var result = swap.rgb
                swap.crop.release()
                swap.affine.release()

                onProgress(0.55f, "Matching skin tone")
                val parserSkinFrame = parse?.let {
                    val upscaled = MatUtils.resize(it.skinMask, targetRgb.cols(), targetRgb.rows())
                    it.skinMask.release()
                    upscaled
                }
                parse?.classMap?.release()
                val blended = blender.colourMatch(
                    swapped = result,
                    targetFrame = targetRgb,
                    targetCrop = targetRgb,
                    parse = parserSkinFrame?.let { skin ->
                        // colourMatch expects a parse result; build a lightweight one with the skin mask.
                        FaceBlender.ParseResult(
                            skinMask = skin,
                            occlusionMask = Mat.zeros(targetRgb.size(), org.opencv.core.CvType.CV_32FC1),
                            classMap = Mat.zeros(512, 512, org.opencv.core.CvType.CV_8UC1),
                        )
                    },
                    landmarks = targetMesh.landmarks,
                    faceId = 0,
                    options = ColorMatcher.BlendOptions(useTemporal = false, usePoisson = true),
                )
                parserSkinFrame?.release()
                result.release()
                result = blended

                onProgress(0.72f, "Restoring pores and detail")
                if (settings.hyperRealistic) {
                    val fidelity = HyperRealEnhancer.sliderToFidelity(settings.enhancerStrength)
                    val affine = FaceWarp.estimateAffine(targetLandmarks, FaceWarp.FFHQ_512, 512)
                    val mask = colorsForMesh(targetMesh, result)
                    val enhanced = enhancer.enhanceFrame(result, affine, fidelity, mask)
                    if (!enhanced) warnings += "Hyper realistic enhancer is not installed - detail pass skipped."
                    mask.release()
                    affine.release()
                }
                // Grain is independent of the detail pass: GFPGAN smooths skin, the grain pass puts
                // the sensor texture back. Off means the swap keeps the enhancer's clean look.
                if (settings.grainMatch) {
                    onProgress(0.82f, "Matching film grain")
                    blender.addGrain(result, targetMesh.bounds, null, amount = 0.85f)
                }

                onProgress(0.86f, "Refining eyes")
                blender.eyeRefine(result, targetMesh.landmarks)
                targetLandmarks.release()
                occlusionFrame?.release()

                var finalMat = result
                if (settings.upscaleTo4k && enhancer.isUpscalerAvailable() && max(target.width, target.height) < 2160) {
                    onProgress(0.90f, "Upscaling to 4K")
                    val upscaled = enhancer.upscale2x(finalMat, spec)
                    if (upscaled != null) {
                        finalMat.release()
                        finalMat = upscaled
                    } else {
                        warnings += "Upscaler is not installed - exporting at the original resolution."
                    }
                }

                val bitmap = BitmapUtils.rgbToBitmap(finalMat)
                if (finalMat !== result) result.release()
                finalMat.release()
                output = null
                onProgress(1f, "Done")
                PhotoSwapResult(
                    bitmap = bitmap,
                    identityScore = swap.identityScore,
                    width = bitmap.width,
                    height = bitmap.height,
                    elapsedMs = System.currentTimeMillis() - started,
                    warnings = warnings,
                )
            } finally {
                pipeline.close()
            }
        } finally {
            sourceRgb.release()
            targetRgb.release()
            output?.release()
        }
    }

    /** Skin mask of [mesh] at the size of [frame] (small helper so the pipeline reads linearly). */
    private fun colorsForMesh(mesh: MeshFace, frame: Mat): Mat =
        ColorMatcher().createFeatheredSkinMask(mesh, frame.cols(), frame.rows())

    /** App cache directory, where the staged result images live. */
    fun cacheDir(): File = context.cacheDir

    /**
     * Crops every detected face into a small square thumbnail for the face selector strip.
     * Faces below [FaceDetector.MINIMUM_FACE_PX] are still shown (the UI explains the quality loss).
     */
    suspend fun faceThumbnails(bitmap: Bitmap, faces: List<DetectedFace>, size: Int = 160): List<Bitmap> =
        withContext(Dispatchers.Default) {
            val rgb = BitmapUtils.bitmapToRgb(bitmap)
            try {
                faces.map { face ->
                    val crop = detector.cropFace(rgb, face, margin = 0.25)
                    val square = MatUtils.ensureRgb(crop, size, size)
                    crop.release()
                    val thumb = BitmapUtils.rgbToBitmap(square)
                    square.release()
                    thumb
                }
            } finally {
                rgb.release()
            }
        }

    /** High resolution crop of one face (used by the "selected face" preview). */
    suspend fun cropFace(bitmap: Bitmap, face: DetectedFace): Bitmap = withContext(Dispatchers.Default) {
        val rgb = BitmapUtils.bitmapToRgb(bitmap)
        try {
            val crop = detector.cropFace(rgb, face, margin = 0.15)
            val out = BitmapUtils.rgbToBitmap(crop)
            crop.release()
            out
        } finally {
            rgb.release()
        }
    }

    /** Detects faces in a bitmap (used by the video screen to validate the identity donor). */
    suspend fun detectFaces(bitmap: Bitmap): List<DetectedFace> = withContext(Dispatchers.Default) {
        val rgb = BitmapUtils.bitmapToRgb(bitmap)
        try {
            detector.detect(rgb)
        } finally {
            rgb.release()
        }
    }

    /** Finds the face under a tap position (image pixels). */
    fun faceAt(faces: List<DetectedFace>, x: Float, y: Float, width: Int, height: Int): DetectedFace? =
        detector.faceAt(faces, x, y, width, height)

    /**
     * Draws the 478 point mesh + silhouette + eye/lip rings on a copy of [bitmap].
     *
     * This is the "mesh overlay toggle" debug view: it makes it obvious whether the tracker is locked on
     * (dots follow the face, iris points sit in the pupils) before the user spends a minute on a 4K swap.
     */
    suspend fun meshOverlay(
        bitmap: Bitmap,
        mesh: com.swapstudio.ai.core.mesh.MeshFace,
        accentArgb: Int = MESH_ACCENT_MINT,
    ): Mat = withContext(Dispatchers.Default) {
        val rgb = BitmapUtils.bitmapToRgb(bitmap)
        val canvas = rgb.clone()
        val accent = argbToRgbScalar(accentArgb)
        // Every landmark as a small dot.
        mesh.landmarks.forEachIndexed { index, point ->
            val radius = if (index >= FaceGeometry.MESH_POINT_COUNT) 2 else 1
            Imgproc.circle(canvas, point, radius, accent, -1, Imgproc.LINE_AA, 0)
        }
        // Silhouette.
        val oval = org.opencv.core.MatOfPoint(*FaceGeometry.FACE_OVAL.map { mesh.landmarks[it] }.toTypedArray())
        Imgproc.polylines(canvas, listOf(oval), true, org.opencv.core.Scalar(124.0, 92.0, 255.0), 2, Imgproc.LINE_AA, 0)
        oval.release()
        // Eyes and lips.
        listOf(FaceGeometry.RIGHT_EYE_RING, FaceGeometry.LEFT_EYE_RING, FaceGeometry.LIPS_OUTER).forEach { ring ->
            val poly = org.opencv.core.MatOfPoint(*ring.map { mesh.landmarks[it] }.toTypedArray())
            Imgproc.polylines(canvas, listOf(poly), true, org.opencv.core.Scalar(255.0, 61.0, 113.0), 2, Imgproc.LINE_AA, 0)
            poly.release()
        }
        // Iris centres get a ring so a failed iris detection is immediately visible.
        if (mesh.landmarks.size > FaceGeometry.LEFT_IRIS_CENTER) {
            Imgproc.circle(canvas, mesh.landmarks[FaceGeometry.RIGHT_IRIS_CENTER], 4, org.opencv.core.Scalar(255.0, 176.0, 32.0), 1, Imgproc.LINE_AA, 0)
            Imgproc.circle(canvas, mesh.landmarks[FaceGeometry.LEFT_IRIS_CENTER], 4, org.opencv.core.Scalar(255.0, 176.0, 32.0), 1, Imgproc.LINE_AA, 0)
        }
        rgb.release()
        canvas
    }

    /**
     * Converts an ARGB colour (what DataStore stores and Compose renders) into an OpenCV scalar in
     * **RGB** channel order, which is the order this pipeline works in.
     */
    private fun argbToRgbScalar(argb: Int): org.opencv.core.Scalar = org.opencv.core.Scalar(
        ((argb shr 16) and 0xFF).toDouble(),
        ((argb shr 8) and 0xFF).toDouble(),
        (argb and 0xFF).toDouble(),
    )

    /** Chooses the mesh that best matches a detector box. */
    private fun pickMesh(meshes: List<MeshFace>, face: DetectedFace): MeshFace? {
        if (meshes.isEmpty()) return null
        if (meshes.size == 1) return meshes[0]
        val index = meshAnalyzer.matchMesh(meshes, face.box)
        return if (index >= 0) meshes[index] else meshes.maxByOrNull { it.bounds[2] * it.bounds[3] }
    }

    /** Builds the ONNX session spec for a performance mode. */
    fun sessionSpec(mode: PerformanceMode): SessionSpec = when (mode) {
        PerformanceMode.BATTERY_SAVER -> SessionSpec(
            threads = 2,
            kind = EpKind.CPU,
            fp16 = true,
            htpPerformanceMode = com.swapstudio.ai.core.inference.HtpPerformanceMode.POWER_SAVER,
        )
        PerformanceMode.BALANCED -> SessionSpec(
            threads = 4,
            kind = EpKind.NNAPI,
            fp16 = true,
            htpPerformanceMode = com.swapstudio.ai.core.inference.HtpPerformanceMode.BALANCED,
        )
        PerformanceMode.MAX -> SessionSpec(
            threads = 4,
            kind = EpKind.QNN,
            fp16 = true,
            htpPerformanceMode = com.swapstudio.ai.core.inference.HtpPerformanceMode.SUSTAINED_HIGH_PERFORMANCE,
        )
    }

    /** True when every required model is on disk. */
    suspend fun isReady(): Boolean = withContext(Dispatchers.IO) { models.requiredForPhoto().all { it.exists() } }

    /** Saves a bitmap as JPEG (quality 100) or PNG into [target]. */
    suspend fun saveBitmap(bitmap: Bitmap, target: File, png: Boolean) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        target.outputStream().use { stream ->
            if (png) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            } else {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            }
        }
        target
    }

    /** Not used by the pipeline itself, but handy in debug builds. */
    fun debugDump(mat: Mat, file: File) {
        try {
            val bitmap = BitmapUtils.rgbToBitmap(mat)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } catch (t: Throwable) {
            Log.w(TAG, "debug dump failed", t)
        }
    }

    private companion object {
        const val TAG = "PhotoSwapPipeline"
    }
}
