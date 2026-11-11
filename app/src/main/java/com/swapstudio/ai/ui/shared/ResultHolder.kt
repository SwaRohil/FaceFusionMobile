package com.swapstudio.ai.ui.shared

import android.graphics.Bitmap
import android.net.Uri
import java.io.File

/**
 * Hand-off between a swap screen and the result screen.
 *
 * The navigation graph is deliberately stateless, and a full resolution 4K bitmap cannot travel through a
 * Bundle. This singleton keeps the finished media in memory (plus the cached file on disk, which survives
 * process death) and the result screen simply reads it. It is cleared when the result screen closes, so
 * the memory is released as soon as the user is done.
 */
object ResultHolder {

    /** A finished photo swap. */
    data class PhotoResult(
        val after: Bitmap,
        val before: Bitmap,
        val identityScore: Float,
        val width: Int,
        val height: Int,
        val elapsedMs: Long,
        val warnings: List<String>,
        val cacheFile: File,
    )

    /** A finished video swap. */
    data class VideoResult(
        val videoFile: File,
        val thumbnail: Bitmap?,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val frameCount: Int,
        val fps: Float,
        val hasAudio: Boolean,
        val elapsedMs: Long,
        val warnings: List<String>,
        val beforeThumbnail: Bitmap? = null,
    )

    /** A gallery entry written by the export manager. */
    data class SavedExport(
        val uri: Uri,
        val displayPath: String,
        val mimeType: String,
        val bytes: Long,
        val width: Int,
        val height: Int,
    )

    @Volatile
    var photo: PhotoResult? = null
        private set

    @Volatile
    var video: VideoResult? = null
        private set

    @Volatile
    var lastSaved: SavedExport? = null

    fun setPhoto(
        after: Bitmap,
        before: Bitmap,
        identityScore: Float,
        width: Int,
        height: Int,
        elapsedMs: Long,
        warnings: List<String>,
        cacheFile: File,
    ) {
        photo = PhotoResult(after, before, identityScore, width, height, elapsedMs, warnings, cacheFile)
    }

    fun setVideo(result: VideoResult) {
        video = result
    }

    fun setSaved(export: SavedExport) {
        lastSaved = export
    }

    /** True when there is something to show. */
    fun hasPhoto(): Boolean = photo != null

    fun hasVideo(): Boolean = video != null

    /** Drops the photo result (bitmaps are recycled) — called when the result screen leaves. */
    fun clearPhoto() {
        photo = null
    }

    /** Drops the video result (the file stays in the cache until the app is closed). */
    fun clearVideo() {
        video = null
    }

    fun clearAll() {
        photo = null
        video = null
    }
}
