package com.swapstudio.ai.ui.screens

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swapstudio.ai.MainActivity
import com.swapstudio.ai.core.background.SwapForegroundService
import com.swapstudio.ai.core.export.ExportManager
import com.swapstudio.ai.core.export.VideoInfo
import com.swapstudio.ai.ui.shared.ResultHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** State of the result screen. */
data class ResultUiState(
    val saving: Boolean = false,
    val saveProgress: Int = 0,
    val savedPath: String? = null,
    val savedUri: Uri? = null,
    val savedMime: String = "video/mp4",
    val videoInfo: VideoInfo? = null,
    val error: String? = null,
    val exportPng: Boolean = false,
    val shareTargets: List<String> = emptyList(),
) {
    val isSaved: Boolean get() = savedUri != null
}

/**
 * Result screen logic: save to the gallery with progress, share to WhatsApp / TikTok / Instagram, and
 * read back the metadata of the written file so the quality bar (4K, 20+ Mbps, audio kept) is verifiable
 * on screen instead of being a promise in a readme.
 */
@HiltViewModel
class ResultViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exports: ExportManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ResultUiState())
    val state: StateFlow<ResultUiState> = _state.asStateFlow()

    /** Saves the current result (photo or video) into DCIM/SwapStudio. */
    fun saveToGallery(png: Boolean = false) {
        if (_state.value.saving || _state.value.isSaved) return
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, saveProgress = 0, error = null)
            try {
                val photo = ResultHolder.photo
                val video = ResultHolder.video
                when {
                    video != null -> {
                        SwapForegroundService.start(context, "Saving to gallery", "Copying video", 0)
                        val staged = exports.stageInCache(video.videoFile)
                        val result = exports.saveVideo(staged) { percent ->
                            _state.value = _state.value.copy(saveProgress = percent)
                            SwapForegroundService.update(context, "Saving to gallery", "$percent %", percent)
                        }
                        SwapForegroundService.stop(context)
                        val info = exports.videoInfo(video.videoFile)
                        ResultHolder.setSaved(
                            ResultHolder.SavedExport(result.uri, result.displayPath, result.mimeType, result.bytes, result.width, result.height),
                        )
                        _state.value = _state.value.copy(
                            saving = false,
                            saveProgress = 100,
                            savedPath = result.displayPath,
                            savedUri = result.uri,
                            savedMime = result.mimeType,
                            videoInfo = info,
                        )
                        notifySaved(result.displayPath, result.uri, result.mimeType)
                    }
                    photo != null -> {
                        val result = exports.savePhoto(photo.after, png = png) { percent ->
                            _state.value = _state.value.copy(saveProgress = percent)
                        }
                        ResultHolder.setSaved(
                            ResultHolder.SavedExport(result.uri, result.displayPath, result.mimeType, result.bytes, result.width, result.height),
                        )
                        _state.value = _state.value.copy(
                            saving = false,
                            saveProgress = 100,
                            savedPath = result.displayPath,
                            savedUri = result.uri,
                            savedMime = result.mimeType,
                        )
                        notifySaved(result.displayPath, result.uri, result.mimeType)
                    }
                    else -> _state.value = _state.value.copy(saving = false, error = "Nothing to save")
                }
            } catch (t: Throwable) {
                SwapForegroundService.stop(context)
                _state.value = _state.value.copy(saving = false, error = t.message ?: "Save failed")
            }
        }
    }

    /** Share intent for the saved (or staged) result. */
    fun shareIntent(): Intent? {
        val saved = _state.value.savedUri
        val photo = ResultHolder.photo
        val video = ResultHolder.video
        return when {
            saved != null -> exports.shareIntent(saved, _state.value.savedMime)
            video != null -> exports.shareFile(video.videoFile, "video/mp4")
            photo != null -> exports.shareFile(photo.cacheFile, "image/jpeg")
            else -> null
        }
    }

    /** Intent that opens the saved file in the gallery app. */
    fun openIntent(): Intent? {
        val uri = _state.value.savedUri ?: return null
        return exports.openIntent(uri, _state.value.savedMime)
    }

    /** True when the file meets the 4K / 20 Mbps bar (called from the UI). */
    fun meetsQualityBar(): Boolean = _state.value.videoInfo?.meetsQualityBar ?: false

    /** Human readable bitrate for the quality card. */
    fun bitrateLabel(): String = _state.value.videoInfo?.let { exports.formatBitrate(it.bitrate) } ?: "-"

    /** Human readable size. */
    fun sizeLabel(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024))
        bytes > 0 -> "${bytes / 1024} KB"
        else -> "-"
    }

    /** Deletes the just saved export. */
    fun deleteSaved() {
        val uri = _state.value.savedUri ?: return
        viewModelScope.launch {
            exports.delete(uri)
            _state.value = _state.value.copy(savedUri = null, savedPath = null, saveProgress = 0)
        }
    }

    /** Posts the "Saved" notification with an OPEN action (the snackbar has the same action). */
    private fun notifySaved(path: String, uri: Uri, mime: String) {
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        SwapForegroundService.notifySaved(context, path, open)
    }

    /** File handle of the staged result, for the ExoPlayer preview. */
    fun stagedVideo(): File? = ResultHolder.video?.videoFile

    /** Clears the in-memory result once the user leaves the screen. */
    fun release() {
        viewModelScope.launch(Dispatchers.IO) {
            ResultHolder.clearAll()
        }
    }
}
