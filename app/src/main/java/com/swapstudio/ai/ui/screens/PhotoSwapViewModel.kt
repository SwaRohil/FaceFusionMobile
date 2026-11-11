package com.swapstudio.ai.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swapstudio.ai.core.detection.FaceDetector
import com.swapstudio.ai.core.models.ModelManager
import com.swapstudio.ai.core.safety.ContentVerdict
import com.swapstudio.ai.core.safety.MediaSafety
import com.swapstudio.ai.core.swap.PerformanceMode
import com.swapstudio.ai.core.swap.PhotoAnalysis
import com.swapstudio.ai.core.swap.PhotoSwapPipeline
import com.swapstudio.ai.core.swap.PhotoSwapSettings
import com.swapstudio.ai.core.util.BitmapUtils
import com.swapstudio.ai.data.AppSettings
import com.swapstudio.ai.data.SettingsRepository
import com.swapstudio.ai.ui.shared.ResultHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Which slot a photo picker is filling. */
enum class PhotoSlot { SOURCE, TARGET }

/**
 * Everything the photo swap screen renders.
 *
 * @param analysis detection results for both images (faces + meshes + warnings)
 * @param busy true during detection or the swap itself
 * @param progress 0..1 with a stage label
 * @param blocked non-null when content safety refused an image
 */
data class PhotoSwapUiState(
    val sourceUri: Uri? = null,
    val targetUri: Uri? = null,
    val sourcePreview: Bitmap? = null,
    val targetPreview: Bitmap? = null,
    val sourceFaces: List<Bitmap> = emptyList(),
    val targetFaces: List<Bitmap> = emptyList(),
    val sourceIndex: Int = 0,
    val targetIndex: Int = 0,
    val analysis: PhotoAnalysis? = null,
    val busy: Boolean = false,
    val progress: Float = 0f,
    val stage: String = "",
    val blocked: String? = null,
    val error: String? = null,
    val warnings: List<String> = emptyList(),
    val consentAccepted: Boolean = false,
    val hyperRealistic: Boolean = true,
    val enhancerStrength: Int = 70,
    val showMesh: Boolean = false,
    val exportPng: Boolean = false,
    val upscale4k: Boolean = true,
    val performanceMode: PerformanceMode = PerformanceMode.MAX,
    val lastIdentityScore: Float = 0f,
) {
    /** Both images picked and at least one face in each. */
    val canSwap: Boolean
        get() = sourceUri != null && targetUri != null && !busy && consentAccepted &&
            (analysis?.sourceFaces?.isNotEmpty() ?: false) && (analysis?.targetFaces?.isNotEmpty() ?: false)
}

/**
 * Photo screen logic: picking images, detecting faces, running the swap and handing the result to the
 * result screen through [ResultHolder].
 */
@HiltViewModel
class PhotoSwapViewModel @Inject constructor(
    private val pipeline: PhotoSwapPipeline,
    private val models: ModelManager,
    private val safety: MediaSafety,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PhotoSwapUiState())
    val state: StateFlow<PhotoSwapUiState> = _state.asStateFlow()

    /** Full resolution bitmaps, kept out of the UI state to avoid recompositions. */
    private var sourceBitmap: Bitmap? = null
    private var targetBitmap: Bitmap? = null

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
            showMesh = settings.showMesh,
            exportPng = settings.exportPng,
            upscale4k = settings.upscale4k,
            performanceMode = settings.performanceMode,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Picking
    // ---------------------------------------------------------------------------------------------

    /** Loads the picked image into the given slot and re-runs detection. */
    fun onImagePicked(slot: PhotoSlot, uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, stage = "Loading photo", error = null, blocked = null)
            try {
                val bitmap = pipeline.loadBitmap(uri, maxDimension = 4096)
                when (slot) {
                    PhotoSlot.SOURCE -> {
                        sourceBitmap?.recycle()
                        sourceBitmap = bitmap
                        _state.value = _state.value.copy(sourceUri = uri, sourcePreview = bitmap)
                        settingsRepository.setLastSource(uri.toString())
                    }
                    PhotoSlot.TARGET -> {
                        targetBitmap?.recycle()
                        targetBitmap = bitmap
                        _state.value = _state.value.copy(targetUri = uri, targetPreview = bitmap)
                        settingsRepository.setLastTarget(uri.toString())
                    }
                }
                val verdict = safety.checkImage(bitmap)
                if (verdict is ContentVerdict.Blocked) {
                    _state.value = _state.value.copy(busy = false, blocked = verdict.reason, stage = "")
                    return@launch
                }
                detect()
            } catch (t: Throwable) {
                _state.value = _state.value.copy(busy = false, error = t.message ?: "Cannot load image", stage = "")
            }
        }
    }

    /** Runs detection on both images (when present). */
    private suspend fun detect() {
        val source = sourceBitmap ?: return
        val target = targetBitmap ?: return
        _state.value = _state.value.copy(busy = true, stage = "Detecting faces", progress = 0.1f)
        try {
            val analysis = pipeline.analyse(source, target)
            val sourceThumbs = withContext(Dispatchers.Default) {
                pipeline.faceThumbnails(source, analysis.sourceFaces)
            }
            val targetThumbs = withContext(Dispatchers.Default) {
                pipeline.faceThumbnails(target, analysis.targetFaces)
            }
            _state.value = _state.value.copy(
                analysis = analysis,
                sourceFaces = sourceThumbs,
                targetFaces = targetThumbs,
                sourceIndex = analysis.sourceIndex,
                targetIndex = analysis.targetIndex,
                warnings = analysis.warnings,
                busy = false,
                progress = 0f,
                stage = "",
            )
        } catch (t: Throwable) {
            _state.value = _state.value.copy(busy = false, error = t.message ?: "Detection failed", stage = "")
        }
    }

    fun selectSourceFace(index: Int) {
        _state.value = _state.value.copy(sourceIndex = index)
    }

    fun selectTargetFace(index: Int) {
        _state.value = _state.value.copy(targetIndex = index)
    }

    /** Swaps the roles of the two images. */
    fun swapRoles() {
        val source = sourceBitmap
        val target = targetBitmap
        val (sourceUri, targetUri) = _state.value.sourceUri to _state.value.targetUri
        sourceBitmap = target
        targetBitmap = source
        _state.value = _state.value.copy(
            sourceUri = targetUri,
            targetUri = sourceUri,
            sourcePreview = target,
            targetPreview = source,
            sourceFaces = _state.value.targetFaces,
            targetFaces = _state.value.sourceFaces,
        )
        viewModelScope.launch { detect() }
    }

    // ---------------------------------------------------------------------------------------------
    // Settings toggles (persisted)
    // ---------------------------------------------------------------------------------------------

    fun setConsent(value: Boolean) {
        viewModelScope.launch { settingsRepository.setConsent(value) }
    }

    fun setHyperRealistic(value: Boolean) {
        viewModelScope.launch { settingsRepository.setHyperRealistic(value) }
    }

    fun setEnhancerStrength(value: Int) {
        viewModelScope.launch { settingsRepository.setEnhancerStrength(value) }
    }

    fun setShowMesh(value: Boolean) {
        viewModelScope.launch { settingsRepository.setShowMesh(value) }
    }

    fun setExportPng(value: Boolean) {
        viewModelScope.launch { settingsRepository.setExportPng(value) }
    }

    fun setUpscale4k(value: Boolean) {
        viewModelScope.launch { settingsRepository.setUpscale4k(value) }
    }

    fun setPerformanceMode(mode: PerformanceMode) {
        viewModelScope.launch { settingsRepository.setPerformanceMode(mode) }
    }

    // ---------------------------------------------------------------------------------------------
    // Swap
    // ---------------------------------------------------------------------------------------------

    /** Runs the swap and stores the result for the result screen. */
    fun runSwap(onFinished: () -> Unit) {
        val source = sourceBitmap ?: return
        val target = targetBitmap ?: return
        val current = _state.value
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null, progress = 0f, stage = "Starting")
            try {
                val settings = PhotoSwapSettings(
                    hyperRealistic = current.hyperRealistic,
                    enhancerStrength = current.enhancerStrength,
                    upscaleTo4k = current.upscale4k,
                    exportPng = current.exportPng,
                    showMesh = current.showMesh,
                    performanceMode = current.performanceMode,
                )
                val result = pipeline.swap(
                    source = source,
                    target = target,
                    settings = settings,
                    sourceIndex = current.sourceIndex,
                    targetIndex = current.targetIndex,
                ) { progress, stage ->
                    _state.value = _state.value.copy(progress = progress, stage = stage)
                }

                // Persist a working copy so the result screen survives process death.
                val cacheFile = File(pipeline.cacheDir(), "swap_result.jpg")
                pipeline.saveBitmap(result.bitmap, cacheFile, png = false)
                ResultHolder.setPhoto(
                    after = result.bitmap,
                    before = target,
                    identityScore = result.identityScore,
                    width = result.width,
                    height = result.height,
                    elapsedMs = result.elapsedMs,
                    warnings = result.warnings,
                    cacheFile = cacheFile,
                )
                HomeViewModel.lastFps = 0f
                _state.value = _state.value.copy(
                    busy = false,
                    progress = 1f,
                    stage = "",
                    lastIdentityScore = result.identityScore,
                    warnings = result.warnings,
                )
                onFinished()
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    busy = false,
                    progress = 0f,
                    stage = "",
                    error = t.message ?: "Swap failed",
                )
            }
        }
    }

    /** True when the required models are installed. */
    suspend fun modelsReady(): Boolean = models.requiredForPhoto().all { it.exists() }

    /** Number of faces found in the target image. */
    fun targetFaceCount(): Int = _state.value.analysis?.targetFaces?.size ?: 0

    /** Full resolution target bitmap, for the mesh overlay preview. */
    fun targetBitmapOrNull(): Bitmap? = targetBitmap

    /** Sharpness of the selected source face, for the quality hint. */
    fun sourceSharpness(): Float = _state.value.analysis?.sourceSharpness ?: 0f

    /** Threshold below which the source face is considered too soft. */
    fun sharpnessWarningLevel(): Float = FaceDetector.SHARPNESS_WARNING

    /** Detects a face under a tap (used to switch the selected target face). */
    fun onTargetTap(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val bitmap = targetBitmap ?: return
        val analysis = _state.value.analysis ?: return
        val scaleX = bitmap.width.toFloat() / viewWidth
        val scaleY = bitmap.height.toFloat() / viewHeight
        viewModelScope.launch {
            val faces = withContext(Dispatchers.Default) { analysis.targetFaces }
            val hit = pipeline.faceAt(faces, x * scaleX, y * scaleY, bitmap.width, bitmap.height)
            if (hit != null) {
                val index = faces.indexOf(hit)
                if (index >= 0) _state.value = _state.value.copy(targetIndex = index)
            }
        }
    }

    /** Crops the target face at [index] for the preview overlay. */
    suspend fun debugFaceCrop(index: Int): Bitmap? {
        val bitmap = targetBitmap ?: return null
        val analysis = _state.value.analysis ?: return null
        val face = analysis.targetFaces.getOrNull(index) ?: return null
        return pipeline.cropFace(bitmap, face)
    }

    /**
     * Builds the mesh debug overlay for the target face. The caller gets a Bitmap it owns.
     * Runs off the main thread; the screen calls it from a LaunchedEffect.
     */
    suspend fun meshPreview(): Bitmap? = withContext(Dispatchers.Default) {
        val bitmap = targetBitmap ?: return@withContext null
        val analysis = _state.value.analysis ?: return@withContext null
        if (!_state.value.showMesh) return@withContext null
        val mesh = analysis.targetMesh.getOrNull(analysis.targetIndex) ?: return@withContext null
        val overlay = pipeline.meshOverlay(bitmap, mesh)
        val result = BitmapUtils.rgbToBitmap(overlay)
        overlay.release()
        result
    }

    override fun onCleared() {
        super.onCleared()
        sourceBitmap?.recycle()
        targetBitmap?.recycle()
        sourceBitmap = null
        targetBitmap = null
    }
}
