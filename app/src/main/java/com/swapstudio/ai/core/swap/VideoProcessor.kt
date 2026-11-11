package com.swapstudio.ai.core.swap

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.swapstudio.ai.core.detection.FaceDetector
import com.swapstudio.ai.core.geometry.FaceGeometry
import com.swapstudio.ai.core.inference.EpKind
import com.swapstudio.ai.core.inference.SessionSpec
import com.swapstudio.ai.core.mesh.BlendshapeChannel
import com.swapstudio.ai.core.mesh.FaceMeshAnalyzer
import com.swapstudio.ai.core.mesh.MeshFace
import com.swapstudio.ai.core.models.ModelManager
import com.swapstudio.ai.core.processing.ColorMatcher
import com.swapstudio.ai.core.util.BitmapUtils
import com.swapstudio.ai.core.util.MatUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the video export should produce. */
data class VideoExportOptions(
    /** Output height (1080, 1440, 2160). Width follows the source aspect ratio. */
    val outputHeight: Int = 2160,
    /** H.265 (HEVC) when true, H.264 otherwise. */
    val hevc: Boolean = false,
    /** Target bitrate in bits/s; 20 Mbps is the minimum the spec asks for at 4K. */
    val bitrate: Int = 24_000_000,
    /** Keep the original audio track untouched (`-c:a copy` semantics). */
    val copyAudio: Boolean = true,
    val hyperRealistic: Boolean = true,
    val enhancerStrength: Int = 70,
    val performanceMode: PerformanceMode = PerformanceMode.MAX,
    /** Only swap this face index (null = every face). */
    val targetFaceIndex: Int? = null,
    /** Strength of the swap, 0..1. */
    val blendStrength: Float = 1f,
    val startMs: Long = 0L,
    val endMs: Long = Long.MAX_VALUE,
)

/** Progress + metrics of a running video swap. */
data class VideoProgress(
    val frameIndex: Int = 0,
    val totalFrames: Int = 0,
    val fraction: Float = 0f,
    val fps: Float = 0f,
    val etaSeconds: Long = -1,
    val stage: String = "Preparing",
    val warnings: List<String> = emptyList(),
) {
    val percent: Int get() = (fraction * 100f).roundToInt().coerceIn(0, 100)
}

/** Final output of a video swap. */
data class VideoSwapResult(
    val outputFile: File,
    val width: Int,
    val height: Int,
    val fps: Double,
    val frameCount: Int,
    val durationMs: Long,
    val hasAudio: Boolean,
    val elapsedMs: Long,
    val warnings: List<String>,
)

/**
 * Frame accurate video face swap.
 *
 * Architecture (deliberately mirrored on FaceFusion's frame processor, but using platform codecs):
 *
 * ```
 * MediaExtractor(video)  ->  MediaCodec decoder  ->  YUV_420_888 -> RGB Bitmap
 *       -> MediaPipe mesh (478 pts + 52 blendshapes)
 *       -> TemporalStabilizer (optical flow + Kalman + blendshape EMA 0.85/0.15)
 *       -> FaceSwapper (inswapper_128_fp16) -> FaceBlender (parse/colour/grain/eyes)
 *       -> RGB -> YUV_420_888 -> MediaCodec encoder (H.264/H.265, 20+ Mbps)
 *       -> MediaMuxer (+ audio track copied byte for byte from the source)
 * ```
 *
 * The audio path really is a stream copy: `MediaExtractor` reads the original AAC/Opus samples and the
 * muxer writes them unchanged, which is exactly what `ffmpeg -c:a copy` does.
 *
 * Cancellation: the loop checks `coroutineContext.isActive` on every frame, so the foreground service
 * and the UI can stop a 4K render at any moment.
 */
@Singleton
class VideoProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: ModelManager,
    private val detector: FaceDetector,
    private val meshAnalyzer: FaceMeshAnalyzer,
    private val swapper: FaceSwapper,
    private val blender: FaceBlender,
    private val enhancer: HyperRealEnhancer,
) {

    /**
     * Swaps faces in [input] and writes the result to [output].
     *
     * @param sourceBitmap the identity donor (already decoded, it is used once for the embedding)
     * @param onProgress called on every frame (throttled to ~10 Hz by the caller if needed)
     */
    suspend fun process(
        input: Uri,
        output: File,
        sourceBitmap: android.graphics.Bitmap,
        options: VideoExportOptions = VideoExportOptions(),
        sourceFaceIndex: Int = 0,
        onProgress: (VideoProgress) -> Unit = {},
    ): VideoSwapResult = withContext(Dispatchers.Default) {
        val warnings = mutableListOf<String>()
        val started = System.currentTimeMillis()
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        // ---------------------------------------------------------------- source identity
        onProgress(VideoProgress(stage = "Reading the source identity"))
        val sourceRgb = BitmapUtils.bitmapToRgb(sourceBitmap)
        val spec = sessionSpec(options.performanceMode)
        val pipeline = swapper.createPipeline(spec)
        val stabilizer = TemporalStabilizer()
        val blendshapeEma = BlendshapeEma()
        val colorMatcher = ColorMatcher()
        try {
            val sourceFaces = detector.detect(sourceRgb)
            require(sourceFaces.isNotEmpty()) { "No face in the source photo" }
            val sourceFace = sourceFaces[sourceFaceIndex.coerceIn(0, sourceFaces.size - 1)]
            val sourceMeshes = meshAnalyzer.analyzeImage(sourceBitmap)
            val sourceMesh = sourceMeshes.maxByOrNull { it.bounds[2] * it.bounds[3] }
                ?: error("Mesh failed on the source face")
            val sourceLandmarks = FaceGeometry.fivePoint(sourceMesh.landmarks)
            swapper.prepareSource(pipeline, sourceRgb, sourceLandmarks)
            sourceLandmarks.release()
            sourceRgb.release()

            // ------------------------------------------------------------ container probing
            val probe = MediaExtractor()
            probe.setDataSource(context, input, null)
            val videoTrack = findVideoTrack(probe) ?: error("No video track found")
            val audioTrack = if (options.copyAudio) findAudioTrack(probe) else -1
            val videoFormat = probe.getTrackFormat(videoTrack)
            val srcWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val srcHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val srcRotation = videoFormat.getIntegerOrDefault("rotation-degrees", 0)
            val srcFps = videoFormat.getIntegerOrDefault(MediaFormat.KEY_FRAME_RATE, 30).toDouble()
            val srcDurationUs = videoFormat.getLongOrDefault(MediaFormat.KEY_DURATION, 0L)
            val audioFormat = if (audioTrack >= 0) probe.getTrackFormat(audioTrack) else null
            probe.release()

            // Rotate 90/270 portrait captures: we keep the *displayed* orientation, so the encoder gets
            // the swapped rotation metadata and the pixel work happens at full resolution.
            val swapDimensions = srcRotation == 90 || srcRotation == 270
            val displayWidth = if (swapDimensions) srcHeight else srcWidth
            val displayHeight = if (swapDimensions) srcWidth else srcHeight

            val outHeight = min(options.outputHeight, max(displayHeight, 1080))
            val scale = outHeight.toDouble() / displayHeight
            val outWidth = ((displayWidth * scale) / 2).toInt() * 2 // encoders require even dimensions
            val scaledHeight = (outHeight / 2) * 2

            val totalFrames = if (srcDurationUs > 0) max(1, (srcDurationUs / 1_000_000.0 * srcFps).toInt()) else 0

            // ------------------------------------------------------------ encoder / muxer
            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val encoderFormat = MediaFormat.createVideoFormat(
                if (options.hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC,
                outWidth,
                scaledHeight,
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfoCodec.COLOR_FORMAT_YUV420_PLANAR)
                setInteger(MediaFormat.KEY_BIT_RATE, options.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, srcFps.toInt().coerceAtLeast(24))
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_PROFILE, if (options.hevc) 1 else 8) // HEVC Main / AVC High
                setInteger(MediaFormat.KEY_LEVEL, if (options.hevc) 512 else 512)
                if (!options.hevc) setInteger(MediaFormat.KEY_BITRATE_MODE, 2) // VBR
                if (srcRotation != 0) setInteger("rotation-degrees", srcRotation)
            }
            val encoder = MediaCodec.createEncoderByType(encoderFormat.getString(MediaFormat.KEY_MIME)!!)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var muxerStarted = false
            var framesEncoded = 0

            // ------------------------------------------------------------ decoder
            val extractor = MediaExtractor()
            extractor.setDataSource(context, input, null)
            extractor.selectTrack(videoTrack)
            val decoderFormat = extractor.getTrackFormat(videoTrack)
            val decoder = MediaCodec.createDecoderByType(decoderFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(decoderFormat, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            fun drainEncoder(endOfStream: Boolean): Boolean {
                if (endOfStream) encoder.signalEndOfInputStream()
                var done = false
                while (!done) {
                    val status = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000L else 0L)
                    when {
                        status == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) done = true
                        status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                            if (audioFormat != null && audioTrackIndex < 0) {
                                audioTrackIndex = muxer.addTrack(MediaFormat.createAudioFormat(
                                    audioFormat.getString(MediaFormat.KEY_MIME)!!,
                                    audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                    audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                                ).apply {
                                    if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                                    }
                                    audioFormat.getIntegerOrDefault("csd-0", -1).let { if (it >= 0) setInteger("csd-0", it) }
                                    audioFormat.getIntegerOrDefault("csd-1", -1).let { if (it >= 0) setInteger("csd-1", it) }
                                    audioFormat.getIntegerOrDefault("channel-mask", -1).let { if (it >= 0) setInteger("channel-mask", it) }
                                    audioFormat.getIntegerOrDefault(MediaFormat.KEY_BIT_RATE, -1).let { if (it > 0) setInteger(MediaFormat.KEY_BIT_RATE, it) }
                                })
                            }
                            muxer.start()
                            muxerStarted = true
                        }
                        status >= 0 -> {
                            val encoded = encoder.getOutputBuffer(status) ?: error("encoder buffer null")
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0 && muxerStarted) {
                                encoded.position(bufferInfo.offset)
                                encoded.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(videoTrackIndex, encoded, bufferInfo)
                                framesEncoded++
                            }
                            encoder.releaseOutputBuffer(status, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                done = true
                            } else if (!endOfStream) {
                                done = true
                            }
                        }
                    }
                }
                return true
            }

            currentCoroutineContext().ensureActive()
            var decodedFrames = 0
            var lastProgressEmit = 0L
            val startUs = options.startMs * 1000
            val endUs = if (options.endMs == Long.MAX_VALUE) Long.MAX_VALUE else options.endMs * 1000

            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                // ---- feed the decoder -------------------------------------------------------
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        val time = extractor.sampleTime
                        if (size < 0 || time > endUs) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                }

                // ---- read a decoded frame ---------------------------------------------------
                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    val endOfStream = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val presentationUs = bufferInfo.presentationTimeUs
                    if (bufferInfo.size > 0 && presentationUs >= startUs) {
                        // The output image is only valid until releaseOutputBuffer, so the decode and
                        // the YUV -> ARGB conversion happen first, then the buffer is handed back.
                        val bitmap = decodeFrame(decoder, outIndex, bufferInfo, srcWidth, srcHeight)
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (bitmap != null) {
                            val scaled = if (srcRotation != 0) rotateBitmap(bitmap, srcRotation) else bitmap
                            if (scaled !== bitmap) bitmap.recycle()

                            val processed = processFrame(
                                frameBitmap = scaled,
                                scaledWidth = outWidth,
                                scaledHeight = scaledHeight,
                                options = options,
                                spec = spec,
                                pipeline = pipeline,
                                stabilizer = stabilizer,
                                blendshapeEma = blendshapeEma,
                                colorMatcher = colorMatcher,
                                warnings = warnings,
                            )
                            scaled.recycle()

                            // ---- feed the encoder ------------------------------------------------
                            val inputIndex = encoder.dequeueInputBuffer(10_000)
                            if (inputIndex >= 0) {
                                val buffer = encoder.getInputBuffer(inputIndex)!!
                                buffer.clear()
                                buffer.put(processed)
                                encoder.queueInputBuffer(inputIndex, 0, processed.size, presentationUs, 0)
                            }
                            decodedFrames++
                            val now = System.currentTimeMillis()
                            if (now - lastProgressEmit > 120) {
                                lastProgressEmit = now
                                val fraction = when {
                                    endUs != Long.MAX_VALUE && endUs > startUs ->
                                        ((presentationUs - startUs).toFloat() / (endUs - startUs)).coerceIn(0f, 1f)
                                    totalFrames > 0 -> (decodedFrames.toFloat() / totalFrames).coerceIn(0f, 1f)
                                    else -> 0f
                                }
                                val elapsed = (now - started) / 1000.0
                                val fps = if (elapsed > 0) (decodedFrames / elapsed).toFloat() else 0f
                                val eta = if (fps > 0.1f && totalFrames > decodedFrames) ((totalFrames - decodedFrames) / fps).toLong() else -1
                                onProgress(
                                    VideoProgress(
                                        frameIndex = decodedFrames,
                                        totalFrames = totalFrames,
                                        fraction = fraction,
                                        fps = fps,
                                        etaSeconds = eta,
                                        stage = "Swapping faces",
                                        warnings = warnings.toList(),
                                    ),
                                )
                            }
                        }
                        drainEncoder(endOfStream = false)
                    } else {
                        decoder.releaseOutputBuffer(outIndex, false)
                    }
                    if (endOfStream) outputDone = true
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // decoder output format changed (usually right away); nothing to do.
                }
                if (outputDone) break
            }

            // ---- flush the encoder --------------------------------------------------------
            drainEncoder(endOfStream = true)
            decoder.stop(); decoder.release()
            extractor.release()
            encoder.stop(); encoder.release()

            // ---- audio stream copy --------------------------------------------------------
            if (muxerStarted && audioTrack >= 0 && audioFormat != null && options.copyAudio) {
                copyAudio(input, muxer, audioTrackIndex, bufferInfo)
            }
            if (muxerStarted) muxer.stop()
            muxer.release()

            val result = VideoSwapResult(
                outputFile = output,
                width = outWidth,
                height = scaledHeight,
                fps = srcFps,
                frameCount = framesEncoded,
                durationMs = if (srcDurationUs > 0) srcDurationUs / 1000 else 0,
                hasAudio = audioTrack >= 0 && options.copyAudio,
                elapsedMs = System.currentTimeMillis() - started,
                warnings = warnings,
            )
            onProgress(
                VideoProgress(
                    frameIndex = decodedFrames,
                    totalFrames = max(totalFrames, decodedFrames),
                    fraction = 1f,
                    stage = "Encoding finished",
                    warnings = warnings.toList(),
                ),
            )
            result
        } finally {
            pipeline.close()
            stabilizer.reset()
            blender.release()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Per frame
    // ---------------------------------------------------------------------------------------------

    /**
     * Processes one decoded frame and returns the encoder input in YUV_420_PLANAR (I420) layout.
     */
    private fun processFrame(
        frameBitmap: android.graphics.Bitmap,
        scaledWidth: Int,
        scaledHeight: Int,
        options: VideoExportOptions,
        spec: SessionSpec,
        pipeline: FaceSwapper.SwapPipeline,
        stabilizer: TemporalStabilizer,
        blendshapeEma: BlendshapeEma,
        colorMatcher: ColorMatcher,
        warnings: MutableList<String>,
    ): ByteArray {
        // 1. Mesh (MediaPipe, VIDEO mode: the tracker reuses the previous frame).
        val timestamp = System.currentTimeMillis()
        val meshes = meshAnalyzer.analyzeVideoFrame(frameBitmap, timestamp, useGpu = options.performanceMode != PerformanceMode.BATTERY_SAVER)
        val frame = BitmapUtils.bitmapToRgb(frameBitmap)
        try {
            if (meshes.isEmpty()) {
                // No face this frame: passthrough. This is also what keeps the background stable.
                val scaled = MatUtils.resize(frame, scaledWidth, scaledHeight)
                val bytes = toI420(scaled)
                scaled.release()
                return bytes
            }

            val working = MatUtils.resize(frame, scaledWidth, scaledHeight)
            frame.release()
            // Mesh coordinates are in source pixels; scale them once so they line up with `working`.
            val scaleX = scaledWidth.toDouble() / frameBitmap.width
            val scaleY = scaledHeight.toDouble() / frameBitmap.height

            meshes.forEachIndexed { index, mesh ->
                if (options.targetFaceIndex != null && index != options.targetFaceIndex) return@forEachIndexed
                val scaledLandmarks = Array(mesh.landmarks.size) { i ->
                    Point(mesh.landmarks[i].x * scaleX, mesh.landmarks[i].y * scaleY)
                }
                val bounds = FaceGeometry.boundsFromMesh(scaledLandmarks)

                // 2. Optical flow + Kalman.
                val gray = Mat()
                Imgproc.cvtColor(working, gray, Imgproc.COLOR_RGB2GRAY)
                val flow = stabilizer.estimateFlow(gray, bounds)
                gray.release()
                val smoothed = stabilizer.smoothLandmarks(scaledLandmarks, flow)

                // 3. Blendshape EMA (0.85 previous + 0.15 new) — keeps blinks and speech but no flicker.
                val shapes = blendshapeEma.push(mesh.blendshapes)
                if (blendshapeEma.isFrozen() && warnings.none { it.startsWith("Expression frozen") }) {
                    warnings += "Expression frozen for 5+ frames: the source clip may be a still image."
                }

                val landmarks = FaceGeometry.fivePoint(smoothed)
                val swap = swapper.swap(
                    pipeline = pipeline,
                    frame = working,
                    targetLandmarks = landmarks,
                    mask = null,
                    enhancer = null,
                    enhancerFidelity = HyperRealEnhancer.sliderToFidelity(options.enhancerStrength),
                    pasteStrength = options.blendStrength,
                )
                landmarks.release()
                swap.crop.release()
                swap.affine.release()
                val swapped = swap.rgb

                // 4. Occlusion + colour (parser session is created once and reused).
                val frame512 = MatUtils.resize(swapped, 512, 512)
                val parse = blender.parseOcclusion(frame512, spec)
                frame512.release()
                val occlusionFrame = parse?.let {
                    val upscaled = MatUtils.resize(it.occlusionMask, scaledWidth, scaledHeight)
                    it.occlusionMask.release()
                    upscaled
                }
                val skinFrame = parse?.let {
                    val upscaled = MatUtils.resize(it.skinMask, scaledWidth, scaledHeight)
                    it.skinMask.release()
                    upscaled
                }
                parse?.classMap?.release()

                val reference = working.clone()
                val matched = colorMatcher.blendFaces(
                    swapped = swapped,
                    target = reference,
                    mesh = syntheticMesh(smoothed, mesh, scaledWidth, scaledHeight, shapes),
                    options = ColorMatcher.BlendOptions(
                        parserMask = skinFrame,
                        useTemporal = true,
                        temporalAlpha = 0.15,
                        usePoisson = true,
                    ),
                    faceId = index,
                )
                reference.release()
                swapped.release()
                matched.copyTo(working)
                matched.release()
                occlusionFrame?.release()
                skinFrame?.release()

                // 5. Eye refinement per frame: pupils must stay crisp in motion.
                blender.eyeRefine(working, smoothed)
            }

            // 6. Grain match on the whole frame keeps the face from looking "cleaner" than the scene.
            if (options.hyperRealistic) {
                val meshesBounds = meshes.firstOrNull()?.let { m ->
                    val points = Array(m.landmarks.size) { i -> Point(m.landmarks[i].x * scaleX, m.landmarks[i].y * scaleY) }
                    FaceGeometry.boundsFromMesh(points)
                }
                if (meshesBounds != null) blender.addGrain(working, meshesBounds, null, amount = 0.5f)
            }

            val bytes = toI420(working)
            working.release()
            return bytes
        } catch (t: Throwable) {
            Log.w(TAG, "frame processing failed, passing the frame through", t)
            val passthrough = MatUtils.resize(frame, scaledWidth, scaledHeight)
            val bytes = toI420(passthrough)
            passthrough.release()
            frame.release()
            return bytes
        }
    }

    /** Builds a MeshFace object for the colour matcher from the smoothed points. */
    private fun syntheticMesh(
        points: Array<Point>,
        original: MeshFace,
        width: Int,
        height: Int,
        blendshapes: FloatArray,
    ): MeshFace = MeshFace(
        landmarks = points,
        normalized = FloatArray(points.size * 3),
        depth = original.depth,
        blendshapes = blendshapes,
        transformation = original.transformation,
        imageWidth = width,
        imageHeight = height,
    )

    // ---------------------------------------------------------------------------------------------
    // Codec plumbing
    // ---------------------------------------------------------------------------------------------

    /**
     * Decodes one output buffer into a Bitmap.
     *
     * `MediaCodec.getOutputImage()` is the only API that exposes the real plane strides of a
     * `COLOR_FormatYUV420Flexible` decoder, and the YUV -> RGB conversion must happen **before**
     * `releaseOutputBuffer`, so the bitmap is built here and the buffer is released by the caller.
     */
    private fun decodeFrame(
        decoder: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
        width: Int,
        height: Int,
    ): android.graphics.Bitmap? {
        val image = try {
            decoder.getOutputImage(index)
        } catch (t: Throwable) {
            null
        }
        if (image != null) {
            return try {
                BitmapUtils.argbArrayToBitmap(yuv420ToArgb(image), image.width, image.height)
            } finally {
                image.close()
            }
        }
        // Fallback for the rare decoder that ignores flex format: assume planar I420.
        val buffer = decoder.getOutputBuffer(index) ?: return null
        val bytes = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.get(bytes)
        return BitmapUtils.argbArrayToBitmap(i420ToArgb(bytes, width, height), width, height)
    }

    /**
     * YUV_420_888 -> ARGB int array.
     *
     * Each plane's `ByteBuffer` is already positioned at that plane's first sample and
     * `pixelStride` describes the interleave, so this indexing is correct for planar I420
     * (`pixelStride == 1`) and for semi-planar NV12/NV21 (`pixelStride == 2`) without having to
     * guess the vendor layout - we never read across planes.
     */
    private fun yuv420ToArgb(image: android.media.Image): IntArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val yRow = y * yRowStride
            val uvRow = y / 2
            val uRowStart = uvRow * uRowStride
            val vRowStart = uvRow * vRowStride
            for (x in 0 until width) {
                val yValue = yBuffer.get(yRow + x * yPixelStride).toInt() and 0xFF
                val uvCol = x / 2
                val uValue = uBuffer.get(uRowStart + uvCol * uPixelStride).toInt() and 0xFF
                val vValue = vBuffer.get(vRowStart + uvCol * vPixelStride).toInt() and 0xFF
                pixels[y * width + x] = yuvToArgb(yValue, uValue, vValue)
            }
        }
        return pixels
    }

    /** BT.601 studio swing YUV -> ARGB (the Android default for video). */
    private fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        val yf = (y - 16).coerceAtLeast(0) * 1.164f
        val uf = u - 128
        val vf = v - 128
        val r = (yf + 1.596f * vf).roundToInt().coerceIn(0, 255)
        val g = (yf - 0.391f * uf - 0.813f * vf).roundToInt().coerceIn(0, 255)
        val b = (yf + 2.018f * uf).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Planar I420 byte array -> ARGB (used by the buffer fallback). */
    private fun i420ToArgb(bytes: ByteArray, width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        val frameSize = width * height
        val uOffset = frameSize
        val vOffset = frameSize + frameSize / 4
        for (y in 0 until height) {
            for (x in 0 until width) {
                val yValue = bytes[y * width + x].toInt() and 0xFF
                val uvIndex = (y / 2) * (width / 2) + (x / 2)
                val uValue = bytes[uOffset + uvIndex].toInt() and 0xFF
                val vValue = bytes[vOffset + uvIndex].toInt() and 0xFF
                pixels[y * width + x] = yuvToArgb(yValue, uValue, vValue)
            }
        }
        return pixels
    }

    /** RGB mat -> I420 byte array (encoder input). */
    fun toI420(rgb: Mat): ByteArray {
        val width = rgb.cols()
        val height = rgb.rows()
        val frameSize = width * height
        val out = ByteArray(frameSize + frameSize / 2)
        val pixels = ByteArray(frameSize * 3)
        rgb.get(0, 0, pixels)
        var yIndex = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = (y * width + x) * 3
                val r = pixels[i].toInt() and 0xFF
                val g = pixels[i + 1].toInt() and 0xFF
                val b = pixels[i + 2].toInt() and 0xFF
                out[yIndex++] = (0.257f * r + 0.504f * g + 0.098f * b + 16f).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        var uvIndex = frameSize
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                for (dy in 0 until 2) for (dx in 0 until 2) {
                    val sx = min(x + dx, width - 1)
                    val sy = min(y + dy, height - 1)
                    val i = (sy * width + sx) * 3
                    rSum += pixels[i].toInt() and 0xFF
                    gSum += pixels[i + 1].toInt() and 0xFF
                    bSum += pixels[i + 2].toInt() and 0xFF
                }
                val r = rSum / 4f
                val g = gSum / 4f
                val b = bSum / 4f
                out[uvIndex++] = (-0.148f * r - 0.291f * g + 0.439f * b + 128f).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        // The V plane follows the U plane (I420 = Y, U, V).
        var vIndex = frameSize + frameSize / 4
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                var rSum = 0
                var gSum = 0
                var bSum = 0
                for (dy in 0 until 2) for (dx in 0 until 2) {
                    val sx = min(x + dx, width - 1)
                    val sy = min(y + dy, height - 1)
                    val i = (sy * width + sx) * 3
                    rSum += pixels[i].toInt() and 0xFF
                    gSum += pixels[i + 1].toInt() and 0xFF
                    bSum += pixels[i + 2].toInt() and 0xFF
                }
                val r = rSum / 4f
                val g = gSum / 4f
                val b = bSum / 4f
                out[vIndex++] = (0.439f * r - 0.368f * g - 0.071f * b + 128f).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** Copies the audio track sample by sample into the muxer (`-c:a copy`). */
    private fun copyAudio(input: Uri, muxer: MediaMuxer, trackIndex: Int, info: MediaCodec.BufferInfo) {
        if (trackIndex < 0) return
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, input, null)
            val audioTrack = findAudioTrack(extractor) ?: return
            extractor.selectTrack(audioTrack)
            val maxSize = extractor.getTrackFormat(audioTrack).getIntegerOrDefault(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
            val buffer = ByteBuffer.allocateDirect(maxSize.coerceAtLeast(64 * 1024))
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(trackIndex, buffer, info)
                extractor.advance()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "audio copy failed, exporting video without audio", t)
        } finally {
            extractor.release()
        }
    }

    private fun findVideoTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        return null
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    private fun rotateBitmap(bitmap: android.graphics.Bitmap, degrees: Int): android.graphics.Bitmap {
        val matrix = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Builds the ONNX session spec for a performance mode (shared with the photo pipeline). */
    fun sessionSpec(mode: PerformanceMode): SessionSpec = when (mode) {
        PerformanceMode.BATTERY_SAVER -> SessionSpec(
            threads = 2,
            kind = EpKind.CPU,
            htpPerformanceMode = com.swapstudio.ai.core.inference.HtpPerformanceMode.POWER_SAVER,
        )
        PerformanceMode.BALANCED -> SessionSpec(
            threads = 4,
            kind = EpKind.NNAPI,
            htpPerformanceMode = com.swapstudio.ai.core.inference.HtpPerformanceMode.BALANCED,
        )
        PerformanceMode.MAX -> SessionSpec(
            threads = 4,
            kind = EpKind.QNN,
            htpPerformanceMode = com.swapstudio.ai.core.inference.HtpPerformanceMode.SUSTAINED_HIGH_PERFORMANCE,
        )
    }

    private companion object {
        const val TAG = "VideoProcessor"
    }

    /** MediaCodec colour formats we ask for (kept local so the file has no hidden imports). */
    private object MediaCodecInfoCodec {
        const val COLOR_FORMAT_YUV420_PLANAR = 19 // MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
    }
}

/** `MediaFormat.getInteger` with a default (used a lot when probing containers). */
internal fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
    if (containsKey(key)) getInteger(key) else default

/** `MediaFormat.getLong` with a default. */
internal fun MediaFormat.getLongOrDefault(key: String, default: Long): Long =
    if (containsKey(key)) getLong(key) else default
