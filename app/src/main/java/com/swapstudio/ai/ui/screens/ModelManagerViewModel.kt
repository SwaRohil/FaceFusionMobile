package com.swapstudio.ai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swapstudio.ai.core.models.DownloadState
import com.swapstudio.ai.core.models.DownloadStatus
import com.swapstudio.ai.core.models.ModelDescriptor
import com.swapstudio.ai.core.models.ModelManager
import com.swapstudio.ai.core.models.StorageInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/** One row of the model manager. */
data class ModelRow(
    val descriptor: ModelDescriptor,
    val state: DownloadState,
    val installed: Boolean,
    val installedBytes: Long,
) {
    /** Button that should be shown for this row. */
    val primaryAction: ModelAction
        get() = when {
            state.status == DownloadStatus.DOWNLOADING || state.status == DownloadStatus.QUEUED -> ModelAction.PAUSE
            state.status == DownloadStatus.PAUSED -> ModelAction.RESUME
            state.status == DownloadStatus.VERIFYING -> ModelAction.NONE
            installed -> ModelAction.DELETE
            else -> ModelAction.DOWNLOAD
        }
}

/** The action a model card offers. */
enum class ModelAction { DOWNLOAD, PAUSE, RESUME, CANCEL, DELETE, UPDATE, NONE }

/** Model manager screen state. */
data class ModelManagerUiState(
    val rows: List<ModelRow> = emptyList(),
    val storage: StorageInfo = StorageInfo(0, 0, 0, 0),
    val downloadingAnything: Boolean = false,
    val message: String? = null,
)

/**
 * Model manager logic.
 *
 * Combines the manifest, the per-model download state flows and the storage statistics into one list of
 * rows. Pause/resume is a first class action: pausing cancels the HTTP call but keeps the `.part` file,
 * resuming sends `Range: bytes=<n>-`, so a 350 MB pack on a flaky connection does not restart from zero.
 */
@HiltViewModel
class ModelManagerViewModel @Inject constructor(
    private val models: ModelManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ModelManagerUiState())
    val state: StateFlow<ModelManagerUiState> = _state.asStateFlow()

    private val message = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            models.ensureLoaded()
            val descriptors = models.models.value
            val states = descriptors.map { models.downloader.stateOf(it.id) }
            combine(states + listOf(models.storage, message)) { values ->
                val downloadStates = values.take(descriptors.size).map { it as DownloadState }
                val storage = values[descriptors.size] as StorageInfo
                val msg = values.lastOrNull() as? String
                val rows = descriptors.mapIndexed { index, descriptor ->
                    ModelRow(
                        descriptor = descriptor,
                        state = downloadStates[index],
                        installed = models.store.isInstalled(descriptor),
                        installedBytes = models.store.installedBytes(descriptor),
                    )
                }
                ModelManagerUiState(
                    rows = rows,
                    storage = storage,
                    downloadingAnything = rows.any {
                        it.state.status == DownloadStatus.DOWNLOADING || it.state.status == DownloadStatus.QUEUED ||
                            it.state.status == DownloadStatus.VERIFYING
                    },
                    message = msg,
                )
            }.collect { _state.value = it }
        }
    }

    /** Runs [action] on one model. */
    fun onAction(row: ModelRow) {
        when (row.primaryAction) {
            ModelAction.DOWNLOAD, ModelAction.RESUME, ModelAction.UPDATE -> {
                val blocked = models.blockedByPolicy()
                if (blocked != null) {
                    // Say it once, clearly, instead of leaving the card at "Paused" with no reason.
                    message.value = blocked.message
                    models.publishBlocked(row.descriptor, blocked)
                } else {
                    models.startDownload(row.descriptor)
                    message.value = "Downloading ${row.descriptor.name}"
                }
            }
            ModelAction.PAUSE -> {
                models.pauseDownload(row.descriptor.id)
                message.value = "Paused - the partial file is kept"
            }
            ModelAction.DELETE -> {
                models.deleteModel(row.descriptor.id)
                message.value = "Deleted ${row.descriptor.name}"
            }
            ModelAction.CANCEL -> {
                models.cancelDownload(row.descriptor.id)
                message.value = "Download cancelled"
            }
            ModelAction.NONE -> Unit
        }
    }

    /** Starts every missing required model, unless the network policy forbids it right now. */
    fun downloadAll() {
        val blocked = models.blockedByPolicy()
        if (blocked != null) {
            message.value = blocked.message
            _state.value.rows.forEach { models.publishBlocked(it.descriptor, blocked) }
            return
        }
        models.downloadAll()
        message.value = "Downloading every required model"
    }

    /** Pauses everything that is currently in flight. */
    fun pauseAll() {
        _state.value.rows.forEach { row ->
            if (row.state.status == DownloadStatus.DOWNLOADING || row.state.status == DownloadStatus.QUEUED) {
                models.pauseDownload(row.descriptor.id)
            }
        }
        message.value = "All downloads paused"
    }

    /** Refreshes the storage numbers (e.g. after a delete). */
    fun refresh() {
        viewModelScope.launch { models.refresh() }
    }

    fun clearMessage() {
        message.value = null
    }

    /** Installed bytes of a row in a human friendly string. */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
        bytes > 0 -> "${(bytes / 1024.0).roundToInt()} KB"
        else -> "-"
    }

    /** Percentage of the total disk used by the models. */
    fun modelShare(): Float {
        val storage = _state.value.storage
        return if (storage.totalBytes > 0) storage.modelsBytes.toFloat() / storage.totalBytes else 0f
    }
}
