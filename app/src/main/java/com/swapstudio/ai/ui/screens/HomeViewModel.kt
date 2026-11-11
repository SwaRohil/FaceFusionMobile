package com.swapstudio.ai.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swapstudio.ai.core.inference.EpKind
import com.swapstudio.ai.core.inference.OrtEngine
import com.swapstudio.ai.core.models.ModelManager
import com.swapstudio.ai.data.AppSettings
import com.swapstudio.ai.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Hardware readout shown on the home screen.
 *
 * @param model the marketing name of the SoC, when the platform exposes it
 * @param ramTotalMb total physical memory
 * @param ramAvailableMb currently available memory
 * @param cores number of CPU cores
 * @param qnnAvailable true when the Hexagon NPU path is usable
 * @param nnapiAvailable true when NNAPI is usable
 * @param fps rolling estimate of the swap throughput (frames per second on the last job)
 */
data class DeviceProfile(
    val model: String = "Unknown",
    val ramTotalMb: Int = 0,
    val ramAvailableMb: Int = 0,
    val cores: Int = 0,
    val qnnAvailable: Boolean = false,
    val nnapiAvailable: Boolean = false,
    val fps: Float = 0f,
    val soc: String = "",
) {
    val ramLabel: String get() = "${ramAvailableMb / 1024} / ${ramTotalMb / 1024} GB free"
}

/**
 * State of the home screen.
 *
 * @param ready true when every required model is installed
 * @param installedCount / totalCount progress of the model manager
 * @param modelsBytes total bytes on disk for the weights
 */
data class HomeUiState(
    val settings: AppSettings = AppSettings(),
    val device: DeviceProfile = DeviceProfile(),
    val ready: Boolean = false,
    val installedCount: Int = 0,
    val totalCount: Int = 0,
    val modelsBytes: Long = 0,
    val missingBytes: Long = 0,
)

/**
 * Backs the home screen: it reads the model status, the device profile and the last used settings, and
 * keeps the RAM/fps readout alive while the screen is visible.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val models: ModelManager,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _settings = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    init {
        viewModelScope.launch {
            models.ensureLoaded()
            refreshDevice()
            refreshModels()
        }
        viewModelScope.launch {
            _settings.collect { value ->
                _state.value = _state.value.copy(settings = value)
            }
        }
        // Live RAM readout; the FPS figure comes from the last completed job.
        viewModelScope.launch {
            while (isActive) {
                delay(2000)
                val memory = memoryInfo()
                _state.value = _state.value.copy(
                    device = _state.value.device.copy(
                        ramAvailableMb = memory.first,
                        ramTotalMb = memory.second,
                        fps = lastFps,
                    ),
                )
            }
        }
    }

    /** Re-reads the installed models (called when returning from the model manager). */
    fun refreshModels() {
        viewModelScope.launch {
            models.refresh()
            val descriptors = models.models.value
            val installed = descriptors.count { models.store.isInstalled(it) }
            val missing = descriptors.filter { it.required && !models.store.isInstalled(it) }
            _state.value = _state.value.copy(
                ready = missing.isEmpty(),
                installedCount = installed,
                totalCount = descriptors.size,
                modelsBytes = models.storage.value.modelsBytes,
                missingBytes = missing.sumOf { it.sizeBytes },
            )
        }
    }

    private fun refreshDevice() {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
        val profile = DeviceProfile(
            model = Build.MODEL ?: "Android",
            soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (Build.SOC_MODEL ?: "") else "",
            ramTotalMb = (memInfo.totalMem / (1024 * 1024)).toInt(),
            ramAvailableMb = (memInfo.availMem / (1024 * 1024)).toInt(),
            cores = Runtime.getRuntime().availableProcessors(),
            qnnAvailable = OrtEngine.qnnAvailable(context.applicationInfo.nativeLibraryDir),
            nnapiAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1,
            fps = lastFps,
        )
        _state.value = _state.value.copy(device = profile)
    }

    private fun memoryInfo(): Pair<Int, Int> {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0 to 0
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return (info.availMem / (1024 * 1024)).toInt() to (info.totalMem / (1024 * 1024)).toInt()
    }

    /** Quick health check shown as a badge on the home screen. */
    fun hardwareSummary(): String {
        val profile = _state.value.device
        return buildString {
            append(profile.cores).append(" cores")
            if (profile.qnnAvailable) append(" - NPU ready") else if (profile.nnapiAvailable) append(" - NNAPI")
            if (profile.ramTotalMb > 0) append(" - ").append(profile.ramTotalMb / 1024).append(" GB RAM")
        }
    }

    /** Turns the last export size into a friendly string ("1.2 GB"). */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
        else -> "${(bytes / 1024.0).roundToInt()} KB"
    }

    /** Cached free space on the data partition, used by the "storage" chip. */
    fun freeDiskGb(): Float {
        val stat = android.os.StatFs(context.filesDir.absolutePath)
        return stat.availableBytes / (1024f * 1024f * 1024f)
    }

    /** Removes scratch renders older than a day. */
    fun clearScratch() {
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            context.cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) file.delete()
            }
        }
    }

    /** Number of model files currently on disk (used by the settings diagnostics). */
    fun installedFileCount(): Int = models.store.listFiles().size

    /** True when the swap pipeline can pick up the QNN provider. */
    fun isNpuAvailable(): Boolean = OrtEngine.qnnAvailable(context.applicationInfo.nativeLibraryDir)

    /** Preferred execution provider for the current performance mode. */
    fun activeExecutionProvider(settings: AppSettings): EpKind = when (settings.performanceMode) {
        com.swapstudio.ai.core.swap.PerformanceMode.BATTERY_SAVER -> EpKind.CPU
        com.swapstudio.ai.core.swap.PerformanceMode.BALANCED -> EpKind.NNAPI
        com.swapstudio.ai.core.swap.PerformanceMode.MAX -> if (isNpuAvailable()) EpKind.QNN else EpKind.NNAPI
    }

    /** Directory that holds the downloaded weights (shown in settings). */
    fun modelsDirectory(): File = models.store.root

    companion object {
        /** Frames per second of the last finished video job, set by the video ViewModel. */
        @Volatile
        var lastFps: Float = 0f
    }
}
