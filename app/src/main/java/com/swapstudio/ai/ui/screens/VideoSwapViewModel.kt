package com.swapstudio.ai.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swapstudio.ai.core.background.SwapForegroundService
import com.swapstudio.ai.core.models.ModelManager
import com.swapstudio.ai.core.safety.ContentVerdict
import com.swapstudio.ai.core.safety.MediaSafety
import com.swapstudio.ai.core.swap.PerformanceMode
import com.swapstudio.ai.core.swap.PhotoSwapPipeline
import com.swapstudio.ai.core.swap.VideoExportOptions
import com.swapstudio.ai.core.swap.VideoProcessor
import com.swapstudio.ai.core.swap.VideoProgress
import com.swapstudio.ai.data.AppSettings
import com.swapstudio.ai.data.SettingsRepository
import com.swapstudio.ai.ui.shared.ResultHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt

/** What to pick next. */
enum class VideoSlot { SOURCE_PHOTO, VIDEO }

/**
 * State of the video screen.
 *
 * @param videoDurationMs / videoWidth / videoHeight shown before rendering so the user knows what they
 *   are about to spend minutes on
 * @param progress live progress with fps and ETA
 */
data class VideoSwapUiState(
    val videoUri: Uri? = null,
    val videoName: String = "",
    val videoDurationMs: Long = 0,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoFps: Double = 0.0,
    val videoThumbnail: Bitmap? = null,
    val sourceUri: Uri? = null,
    val sourcePreview: Bitmap? = null,
    val busy: Boolean = false,
    val progress: VideoProgress = VideoProgress(),
    val error: String? = null,
    val blocked: String? = null,
    val warnings: List<String> = emptyList(),
    val consentAccepted: Boolean = false,
    val hyperRealistic: Boolean = true,
    val enhancerStrength: Int = 70,
    val outputHeight: Int = 2160,
    val hevc: Boolean = false,
    val bitrateMbps: Int = 24,
    val copyAudio: Boolean = true,
    val performanceMode: PerformanceMode = PerformanceMode.MAX,
) {
    val canStart: Boolean
        get() = videoUri != null && sourceUri != null && !busy && consentAccepted
}

/**
 * Video screen logic.
 *
 * The render itself runs in [VideoProcessor] on `Dispatchers.Default`; this ViewModel keeps the progress
 * flow, mirrors it into the foreground service notification and stashes the finished file in
 * [ResultHolder] for the result screen.
 */
@HiltViewModel
class VideoSwapViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val processor: VideoProcessor,
    private val photoPipeline: PhotoSwapPipeline,
    private val models: ModelManager,
    private val safety: MediaSafety,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(VideoSwapUiState())
    val state: StateFlow<VideoSwapUiState> = _state.asStateFlow()

    private var sourceBitmap: Bitmap? = null
    private var renderJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings -> applySettings(settings) }
        }
        viewModelScope.launch { models.ensureLoaded() }
    }

    private fun applySettings(settings: AppSettings) {
        _state.value = _state.value.copy(
            consentAccepted = settings.consentAccepted,
            hyperRealistic = settings.hyperRealistic,
            enhancerStrength = settings.enhancerStrength,
            outputHeight = settings.videoHeight,
            hevc = settings.hevc,
            bitrateMbps = settings.bitrateMbps,
            copyAudio = settings.copyAudio,
            performanceMode = settings.performanceMode,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Picking
    // ---------------------------------------------------------------------------------------------

    /** Loads the target video: metadata, a thumbnail and the duration. */
    fun onVideoPicked(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, blocked = null)
            try {
                val meta = withContext(Dispatchers.IO) { readVideoMetadata(uri) }
                _state.value = _state.value.copy(
                    videoUri = uri,
                    videoName = meta.name,
                    videoDurationMs = meta.durationMs,
                    videoWidth = meta.width,
                    videoHeight = meta.height,
                    videoFps = meta.fps,
                    videoThumbnail = meta.thumbnail,
                    busy = false,
                )
                // Content check on a handful of frames before any long render happens.
                val frames = withContext(Dispatchers.IO) { sampleFrames(uri, 4) }
                when (val verdict = safety.checkVideoFrames(frames)) {
                    is ContentVerdict.Blocked -> _state.value = _state.value.copy(blocked = verdict.reason)
                    is ContentVerdict.Unknown -> _state.value = _state.value.copy(
                        warnings = _state.value.warnings + verdict.reason + " - install the NSFW analyser to enable the content check.",
                    )
                    else -> Unit
                }
                frames.forEach { it.recycle() }
            } catch (t: Throwable) {
                _state.value = _state.value.copy(busy = false, error = t.message ?: "Cannot read the video")
            }
        }
    }

    /** Loads the identity donor photo. */
    fun onSourcePicked(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)
            try {
                val bitmap = photoPipeline.loadBitmap(uri, maxDimension = 2048)
                sourceBitmap?.recycle()
                sourceBitmap = bitmap
                _state.value = _state.value.copy(sourceUri = uri, sourcePreview = bitmap, busy = false)
                when (val verdict = safety.checkImage(bitmap)) {
                    is ContentVerdict.Blocked -> _state.value = _state.value.copy(blocked = verdict.reason)
                    else -> Unit
                }
                val faces = photoPipeline.detectFaces(bitmap)
                if (faces.isEmpty()) {
                    _state.value = _state.value.copy(
                        warnings = _state.value.warnings + "No face found in the source photo.",
                    )
                }
            } catch (t: Throwable) {
                _state.value = _state.value.copy(busy = false, error = t.message ?: "Cannot load the photo")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------------------------------

    fun setConsent(value: Boolean) = viewModelScope.launch { settingsRepository.setConsent(value) }
    fun setHyperRealistic(value: Boolean) = viewModelScope.launch { settingsRepository.setHyperRealistic(value) }
    fun setEnhancerStrength(value: Int) = viewModelScope.launch { settingsRepository.setEnhancerStrength(value) }
    fun setHevc(value: Boolean) = viewModelScope.launch { settingsRepository.setHevc(value) }
    fun setBitrate(mbps: Int) = viewModelScope.launch { settingsRepository.setBitrateMbps(mbps) }
    fun setCopyAudio(value: Boolean) = viewModelScope.launch { settingsRepository.setCopyAudio(value) }
    fun setOutputHeight(height: Int) = viewModelScope.launch { settingsRepository.setVideoHeight(height) }
    fun setPerformanceMode(mode: PerformanceMode) = viewModelScope.launch { settingsRepository.setPerformanceMode(mode) }

    // ---------------------------------------------------------------------------------------------
    // Render
    // ---------------------------------------------------------------------------------------------

    /** Starts the render; [onFinished] is called when the result is ready for the result screen. */
    fun startRender(onFinished: () -> Unit) {
        val videoUri = _state.value.videoUri ?: return
        val source = sourceBitmap ?: return
        if (renderJob?.isActive == true) return
        val current = _state.value
        renderJob = viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, progress = VideoProgress(stage = "Preparing"))
            SwapForegroundService.start(context, "Swapping video", "Preparing", -1)
            try {
                val output = File(context.cacheDir, "swap_result.mp4")
                val options = VideoExportOptions(
                    outputHeight = current.outputHeight,
                    hevc = current.hevc,
                    bitrate = current.bitrateMbps * 1_000_000,
                    copyAudio = current.copyAudio,
                    hyperRealistic = current.hyperRealistic,
                    enhancerStrength = current.enhancerStrength,
                    performanceMode = current.performanceMode,
                )
                val result = processor.process(
                    input = videoUri,
                    output = output,
                    sourceBitmap = source,
                    options = options,
                    onProgress = { progress ->
                        _state.value = _state.value.copy(progress = progress)
                        HomeViewModel.lastFps = progress.fps
                        SwapForegroundService.update(
                            context,
                            "Swapping video",
                            "Frame ${progress.frameIndex}/${progress.totalFrames} - %.1f fps".format(progress.fps),
                            progress.percent,
                        )
                    },
                )
                val thumbnail = withContext(Dispatchers.IO) { frameAt(result.outputFile) }
                val beforeThumb = withContext(Dispatchers.IO) {
                    runCatching {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(context, videoUri)
                        val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        retriever.release()
                        bitmap
                    }.getOrNull()
                }
                ResultHolder.setVideo(
                    ResultHolder.VideoResult(
                        videoFile = result.outputFile,
                        thumbnail = thumbnail,
                        width = result.width,
                        height = result.height,
                        durationMs = result.durationMs,
                        frameCount = result.frameCount,
                        fps = result.fps.toFloat(),
                        hasAudio = result.hasAudio,
                        elapsedMs = result.elapsedMs,
                        warnings = result.warnings,
                        beforeThumbnail = beforeThumb,
                    ),
                )
                _state.value = _state.value.copy(
                    busy = false,
                    progress = _state.value.progress.copy(fraction = 1f, stage = "Done"),
                    warnings = result.warnings,
                )
                SwapForegroundService.stop(context)
                onFinished()
            } catch (t: Throwable) {
                SwapForegroundService.stop(context)
                _state.value = _state.value.copy(
                    busy = false,
                    error = t.message ?: "Render failed",
                    progress = VideoProgress(stage = "Failed"),
                )
            }
        }
    }

    /** Cancels the render (the partial file is deleted by the processor on the next start). */
    fun cancelRender() {
        renderJob?.cancel()
        renderJob = null
        SwapForegroundService.stop(context)
        _state.value = _state.value.copy(busy = false, progress = VideoProgress(stage = "Cancelled"))
    }

    // ---------------------------------------------------------------------------------------------
    // Metadata helpers
    // ---------------------------------------------------------------------------------------------

    private data class VideoMeta(
        val name: String,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val fps: Double,
        val thumbnail: Bitmap?,
    )

    private fun readVideoMetadata(uri: Uri): VideoMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    ?.toDoubleOrNull()?.let { frames -> if (duration > 0) frames / (duration / 1000.0) else 30.0 }
                ?: 30.0
            val name = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "video.mp4"
            val thumbnail = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            VideoMeta(name, duration, width, height, fps, thumbnail)
        } finally {
            retriever.release()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return null
    }

    private fun sampleFrames(uri: Uri, count: Int): List<Bitmap> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (duration <= 0) emptyList() else (1..count).mapNotNull { i ->
                val micros = duration * 1000 / (count + 1) * i
                retriever.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (t: Throwable) {
            emptyList()
        } finally {
            retriever.release()
        }
    }

    /** Grabs the first key frame of a finished file for the result screen poster. */
    private fun frameAt(file: File): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Estimated output size in GB, so the user can decide before a long render. */
    fun estimatedOutputGb(): Float {
        val s = _state.value
        if (s.videoDurationMs <= 0) return 0f
        val bytes = (s.bitrateMbps * 1_000_000L / 8) * (s.videoDurationMs / 1000.0)
        return (bytes / (1024.0 * 1024 * 1024)).toFloat()
    }

    /** Nice "00:42" duration label. */
    fun durationLabel(ms: Long): String {
        val seconds = (ms / 1000.0).roundToInt()
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    override fun onCleared() {
        super.onCleared()
        renderJob?.cancel()
        sourceBitmap?.recycle()
        sourceBitmap = null
    }
}
