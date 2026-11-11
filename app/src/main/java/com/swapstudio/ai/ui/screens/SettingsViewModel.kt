package com.swapstudio.ai.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swapstudio.ai.core.inference.EpKind
import com.swapstudio.ai.core.inference.OrtEngine
import com.swapstudio.ai.core.models.ModelManager
import com.swapstudio.ai.core.swap.PerformanceMode
import com.swapstudio.ai.data.AppSettings
import com.swapstudio.ai.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings screen logic.
 *
 * Every setter writes straight through to DataStore; the screen re-renders from the same flow, so there is
 * no local state to get out of sync.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val models: ModelManager,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun setConsent(value: Boolean) = viewModelScope.launch { settingsRepository.setConsent(value) }
    fun setPerformanceMode(value: PerformanceMode) = viewModelScope.launch { settingsRepository.setPerformanceMode(value) }
    fun setHyperRealistic(value: Boolean) = viewModelScope.launch { settingsRepository.setHyperRealistic(value) }
    fun setEnhancerStrength(value: Int) = viewModelScope.launch { settingsRepository.setEnhancerStrength(value) }
    fun setShowMesh(value: Boolean) = viewModelScope.launch { settingsRepository.setShowMesh(value) }
    fun setExportPng(value: Boolean) = viewModelScope.launch { settingsRepository.setExportPng(value) }
    fun setUpscale4k(value: Boolean) = viewModelScope.launch { settingsRepository.setUpscale4k(value) }
    fun setHevc(value: Boolean) = viewModelScope.launch { settingsRepository.setHevc(value) }
    fun setBitrate(mbps: Int) = viewModelScope.launch { settingsRepository.setBitrateMbps(mbps) }
    fun setCopyAudio(value: Boolean) = viewModelScope.launch { settingsRepository.setCopyAudio(value) }
    fun setGrainMatch(value: Boolean) = viewModelScope.launch { settingsRepository.setGrainMatch(value) }

    /** Wipes preferences and closes the inference sessions (keeps the downloaded weights). */
    fun reset() {
        viewModelScope.launch {
            models.closeAllSessions()
            settingsRepository.clearAll()
        }
    }

    /** Deletes every downloaded model. */
    fun deleteAllModels() {
        viewModelScope.launch {
            models.ensureLoaded()
            models.models.value.forEach { models.deleteModel(it.id) }
        }
    }

    /** Diagnostics shown at the bottom of the screen. */
    fun diagnostics(): List<Pair<String, String>> {
        val qnn = OrtEngine.qnnAvailable(context.applicationInfo.nativeLibraryDir)
        val provider = when {
            qnn -> "QNN (Hexagon NPU + Adreno GPU)"
            else -> "NNAPI / CPU"
        }
        return listOf(
            "Execution provider" to provider,
            "CPU cores" to Runtime.getRuntime().availableProcessors().toString(),
            "Architecture" to (System.getProperty("os.arch") ?: "unknown"),
            "Model directory" to models.store.root.absolutePath,
            "Max heap" to "${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB",
            "Model files" to models.store.listFiles().size.toString(),
        )
    }

    /** Human readable provider for the active mode. */
    fun providerFor(mode: PerformanceMode): EpKind = when (mode) {
        PerformanceMode.BATTERY_SAVER -> EpKind.CPU
        PerformanceMode.BALANCED -> EpKind.NNAPI
        PerformanceMode.MAX -> if (OrtEngine.qnnAvailable(context.applicationInfo.nativeLibraryDir)) EpKind.QNN else EpKind.NNAPI
    }
}
