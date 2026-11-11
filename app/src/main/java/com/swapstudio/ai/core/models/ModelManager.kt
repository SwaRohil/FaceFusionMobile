package com.swapstudio.ai.core.models

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.swapstudio.ai.core.inference.OrtEngine
import com.swapstudio.ai.core.inference.SessionSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Why a model could not be used. */
sealed class ModelIssue {
    data class Missing(val modelId: String) : ModelIssue()
    data class NotEnoughSpace(val requiredBytes: Long, val freeBytes: Long) : ModelIssue()
    data object AllGood : ModelIssue()
}

/**
 * Single source of truth for "which weights are installed, where they are and what state their
 * downloads are in".
 *
 * The manager owns the [ModelDownloader] and a small in-memory session cache: an ONNX session takes
 * hundreds of milliseconds to build, so the video pipeline reuses them frame after frame.
 *
 * Storage policy (as specified): before a download starts we require `free > size * 1.5`, because the
 * archive and its unpacked contents coexist for a moment.
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val catalog = ModelCatalog(context)
    val store = ModelStore(context)
    val downloader = ModelDownloader(store)

    private val _models = MutableStateFlow<List<ModelDescriptor>>(emptyList())
    val models: StateFlow<List<ModelDescriptor>> = _models.asStateFlow()

    private val _storage = MutableStateFlow(StorageInfo(0, 0, 0, 0))
    val storage: StateFlow<StorageInfo> = _storage.asStateFlow()

    private val _installed = MutableStateFlow<Set<String>>(emptySet())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    private val loadMutex = Mutex()
    private var loaded = false

    /** Path of the face landmarker `.task` bundle (MediaPipe asset, not an ONNX session). */
    fun faceLandmarkerFile(): File = store.resolve(FACE_LANDMARKER)

    /** Path of SCRFD det_10g. */
    fun detectorFile(): File = store.resolve("det_10g.onnx")

    /** Path of the ArcFace R50 recogniser. */
    fun arcFaceFile(): File = store.resolve("w600k_r50.onnx")

    /** Path of inswapper_128_fp16. */
    fun swapperFile(): File = store.resolve("inswapper_128_fp16.onnx")

    /** Path of the BiSeNet face parser. */
    fun parserFile(): File = store.resolve("bisenet_resnet_34.onnx")

    /** Path of GFPGAN v1.4. */
    fun enhancerFile(): File = store.resolve("gfpgan_1.4.onnx")

    /** Path of Real-ESRGAN x2 (optional). */
    fun frameEnhancerFile(): File = store.resolve("real_esrgan_x2_fp16.onnx")

    /** Path of the NSFW analyser (optional). */
    fun nsfwFile(): File = store.resolve("open_nsfw.onnx")

    /** All files required to process a photo. */
    fun requiredForPhoto(): List<File> = listOf(faceLandmarkerFile(), detectorFile(), arcFaceFile(), swapperFile())

    /** All files required to process a video (adds the parser; the enhancer is optional). */
    fun requiredForVideo(): List<File> = requiredForPhoto() + parserFile()

    /** Loads the manifest once, then refreshes the storage and installed sets. */
    suspend fun ensureLoaded() {
        loadMutex.withLock {
            if (!loaded) {
                _models.value = catalog.load()
                loaded = true
            }
            refresh()
        }
    }

    /** Re-reads the disk: what is installed and how much space is left. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val descriptors = _models.value
        val installedIds = descriptors.filter { store.isInstalled(it) }.map { it.id }.toSet()
        _installed.value = installedIds
        val free = runCatching { StatFs(store.root.absolutePath).availableBytes }.getOrDefault(0L)
        val total = runCatching { StatFs(store.root.absolutePath).totalBytes }.getOrDefault(0L)
        _storage.value = StorageInfo(
            freeBytes = free,
            totalBytes = total,
            usedBytes = store.usedBytes(),
            modelsBytes = descriptors.sumOf { store.installedBytes(it) },
        )
        descriptors.forEach { descriptor ->
            if (descriptor.id in installedIds) {
                val current = downloader.stateOf(descriptor.id).value
                if (current.status != DownloadStatus.COMPLETED) {
                    downloader.update(
                        DownloadState(descriptor.id, DownloadStatus.COMPLETED, 1f, store.installedBytes(descriptor), store.installedBytes(descriptor), message = "Installed"),
                    )
                }
            }
        }
    }

    /** `size * 1.5 <= free` — the pre-flight test requested for the model manager. */
    fun checkStorage(descriptor: ModelDescriptor): ModelIssue {
        val required = (descriptor.sizeBytes * 1.5).toLong()
        val free = _storage.value.freeBytes
        return if (free > required || required == 0L) ModelIssue.AllGood else ModelIssue.NotEnoughSpace(required, free)
    }

    /**
     * Starts a download, keeping the state flow updated. Safe to call when already installed (it
     * simply verifies and returns).
     */
    fun startDownload(descriptor: ModelDescriptor) {
        scope.launch {
            ensureLoaded()
            if (store.isInstalled(descriptor)) {
                downloader.update(
                    DownloadState(descriptor.id, DownloadStatus.COMPLETED, 1f, store.installedBytes(descriptor), store.installedBytes(descriptor), message = "Installed"),
                )
                return@launch
            }
            when (val issue = checkStorage(descriptor)) {
                is ModelIssue.NotEnoughSpace -> {
                    downloader.update(
                        DownloadState(
                            descriptor.id, DownloadStatus.ERROR,
                            message = "Not enough storage: need ${issue.requiredBytes / MB} MB, ${issue.freeBytes / MB} MB free",
                        ),
                    )
                    return@launch
                }
                else -> Unit
            }
            downloader.update(DownloadState(descriptor.id, DownloadStatus.QUEUED, message = "Starting"))
            downloader.download(descriptor).collect { state ->
                downloader.update(state)
                if (state.status == DownloadStatus.COMPLETED) refresh()
            }
        }
    }

    /** Pauses a transfer; bytes already on disk are kept. */
    fun pauseDownload(modelId: String) = downloader.pause(modelId)

    /** Resumes a paused transfer using an HTTP range request. */
    fun resumeDownload(modelId: String) {
        val descriptor = _models.value.firstOrNull { it.id == modelId } ?: return
        startDownload(descriptor)
    }

    /** Cancels a transfer and forgets the partial file. */
    fun cancelDownload(modelId: String) = downloader.cancel(modelId)

    /** Deletes a model from disk (also closes any cached session that used it). */
    fun deleteModel(modelId: String) {
        val descriptor = _models.value.firstOrNull { it.id == modelId } ?: return
        scope.launch {
            downloader.delete(descriptor)
            closeSession(descriptor.fileName)
            refresh()
        }
    }

    /** Starts every missing model that is not optional. */
    fun downloadAll() {
        scope.launch {
            ensureLoaded()
            _models.value.filter { it.required && !store.isInstalled(it) }.forEach { startDownload(it) }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Session cache
    // -----------------------------------------------------------------------------------------

    private val sessions = mutableMapOf<String, ai.onnxruntime.OrtSession>()

    /**
     * Returns a cached session for [file], creating it with [spec] on first use.
     * The cache key includes the provider+threads so a mode change (Battery/Balanced/Max) rebuilds.
     */
    @Synchronized
    fun sessionFor(file: File, spec: SessionSpec): ai.onnxruntime.OrtSession {
        val key = "${file.absolutePath}|${spec.kind}|${spec.threads}|${spec.fp16}|${spec.htpPerformanceMode}"
        sessions[key]?.let { return it }
        require(file.exists()) { "Model not downloaded: ${file.name}" }
        Log.i(TAG, "creating session ${file.name} on ${spec.kind} (${spec.threads} threads)")
        val session = OrtEngine.createSession(file.absolutePath, spec)
        sessions[key] = session
        return session
    }

    /** Closes every cached session whose file name matches. */
    @Synchronized
    fun closeSession(fileName: String) {
        val keys = sessions.keys.filter { it.contains(fileName) }
        keys.forEach { key ->
            OrtEngine.closeQuietly(sessions.remove(key))
        }
    }

    /** Closes all sessions (called when the app goes to the background for a long time). */
    @Synchronized
    fun closeAllSessions() {
        sessions.values.forEach { OrtEngine.closeQuietly(it) }
        sessions.clear()
    }

    /** Copies a small asset (e.g. the MediaPipe bundle shipped in res/raw) into the model store. */
    suspend fun importAsset(assetName: String, targetName: String = assetName): File = withContext(Dispatchers.IO) {
        val target = File(store.root, targetName)
        if (!target.exists()) {
            context.assets.open(assetName).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        target
    }

    companion object {
        /** Long lived scope: downloads must survive the screen being closed. */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private const val TAG = "ModelManager"
        private const val MB = 1024L * 1024L
        const val FACE_LANDMARKER = "face_landmarker.task"
    }
}

/** Disk statistics for the model manager header. */
data class StorageInfo(
    val freeBytes: Long,
    val totalBytes: Long,
    val usedBytes: Long,
    val modelsBytes: Long,
) {
    /** Fractions used by the storage bar [0..1]. */
    val freeFraction: Float get() = if (totalBytes > 0) (freeBytes.toFloat() / totalBytes) else 0f
    val appFraction: Float get() = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes) else 0f
    val modelsFraction: Float get() = if (totalBytes > 0) (modelsBytes.toFloat() / totalBytes) else 0f

    val freeGb: Float get() = freeBytes / (1024f * 1024f * 1024f)
    val modelsGb: Float get() = modelsBytes / (1024f * 1024f * 1024f)
}
