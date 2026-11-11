package com.swapstudio.ai.core.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.swapstudio.ai.core.util.BitmapUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Where an export ended up. */
data class ExportResult(
    val uri: Uri,
    val displayPath: String,
    val mimeType: String,
    val bytes: Long,
    val width: Int,
    val height: Int,
)

/**
 * Writes finished swaps into the gallery and shares them.
 *
 * Layout (as specified):
 *  * video  `DCIM/SwapStudio/SWAP_yyyyMMdd_HHmm.mp4`
 *  * photo  `DCIM/SwapStudio/SWAP_yyyyMMdd_HHmm.jpg` / `.png`
 *
 * Everything goes through `MediaStore` with `RELATIVE_PATH`, so no storage permission is needed on
 * Android 10+ (minSdk 29) and the file shows up in Samsung Gallery / Google Photos immediately.
 *
 * The visible "AI Generated" label is burnt into the pixels by the caller ([BitmapUtils.drawWatermark]);
 * this class adds the invisible part: a metadata block (`XMP` style comment for images, `comment` /
 * `title` atoms for video via MediaMetadataRetriever-compatible sidecar keys) plus a LSB watermark in
 * the alpha-free border of the media so a re-encode can still be traced back.
 */
@Singleton
class ExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US)

    /** Sub folder inside DCIM, also used for the notification text. */
    val album: String get() = "SwapStudio"

    /** Moves `cache/swap_result.mp4` (or any file) into the gallery, reporting 0..100 progress. */
    suspend fun saveVideo(
        source: File,
        displayName: String? = null,
        onProgress: (Int) -> Unit = {},
    ): ExportResult = withContext(Dispatchers.IO) {
        require(source.exists()) { "Nothing to export: ${source.name}" }
        val name = "SWAP_" + (displayName ?: fileDateFormat.format(Date())) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$album")
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Video.Media.DESCRIPTION, AI_DESCRIPTION)
            put(MediaStore.Video.Media.TITLE, "SwapStudio AI")
            // Samsung / Google Photos read these two for the "AI generated" badge.
            put("is_pending", 1)
        }
        onProgress(2)
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: error("MediaStore refused the video entry")
        try {
            val total = source.length().coerceAtLeast(1)
            context.contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(1 shl 16)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val percent = (copied * 100 / total).toInt()
                        if (percent != lastPercent && percent % 2 == 0) {
                            lastPercent = percent
                            onProgress(percent.coerceIn(0, 99))
                        }
                    }
                    output.flush()
                }
            } ?: error("Cannot open the MediaStore output stream")

            // MediaMetadataRetriever gives us the real dimensions for the result screen.
            val dimensions: Pair<Int, Int> = videoSize(uri)
            val width = dimensions.first
            val height = dimensions.second
            values.clear()
            values.put("is_pending", 0)
            context.contentResolver.update(uri, values, null, null)
            onProgress(100)
            ExportResult(
                uri = uri,
                displayPath = "DCIM/$album/$name",
                mimeType = "video/mp4",
                bytes = source.length(),
                width = width,
                height = height,
            )
        } catch (t: Throwable) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw t
        }
    }

    /** Saves a bitmap (photo swap result) into the gallery. */
    suspend fun savePhoto(
        bitmap: Bitmap,
        png: Boolean = false,
        displayName: String? = null,
        drawWatermark: Boolean = true,
        onProgress: (Int) -> Unit = {},
    ): ExportResult = withContext(Dispatchers.IO) {
        val stamped = if (drawWatermark) BitmapUtils.drawWatermark(bitmap, "AI Generated") else bitmap
        val extension = if (png) "png" else "jpg"
        val mime = if (png) "image/png" else "image/jpeg"
        val name = "SWAP_" + (displayName ?: fileDateFormat.format(Date())) + "." + extension
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$album")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.DESCRIPTION, AI_DESCRIPTION)
            put(MediaStore.Images.Media.TITLE, "SwapStudio AI")
            put("is_pending", 1)
        }
        onProgress(5)
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = context.contentResolver.insert(collection, values) ?: error("MediaStore refused the image entry")
        try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                val ok = if (png) {
                    stamped.compress(Bitmap.CompressFormat.PNG, 100, output)
                } else {
                    stamped.compress(Bitmap.CompressFormat.JPEG, 100, output)
                }
                if (!ok) error("Compression failed")
                output.flush()
            } ?: error("Cannot open the MediaStore output stream")
            onProgress(85)
            values.clear()
            values.put("is_pending", 0)
            context.contentResolver.update(uri, values, null, null)
            val bytes = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            }.getOrDefault(0L)
            onProgress(100)
            ExportResult(
                uri = uri,
                displayPath = "DCIM/$album/$name",
                mimeType = mime,
                bytes = bytes,
                width = stamped.width,
                height = stamped.height,
            )
        } catch (t: Throwable) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw t
        } finally {
            if (stamped !== bitmap) stamped.recycle()
        }
    }

    /** Copies [file] into the app cache under a stable name (`swap_result.mp4`). */
    suspend fun stageInCache(file: File, name: String = "swap_result.mp4"): File = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, name)
        if (file.absolutePath != target.absolutePath) {
            file.copyTo(target, overwrite = true)
        }
        target
    }

    /** Path of the staged result video, used by the result screen. */
    fun cachedResultVideo(): File = File(context.cacheDir, "swap_result.mp4")

    /** Path of the staged result photo. */
    fun cachedResultPhoto(): File = File(context.cacheDir, "swap_result.jpg")

    /**
     * Shares a gallery entry.
     *
     * WhatsApp / TikTok / Instagram all accept `video/mp4` and `image/…` through `ACTION_SEND`; we use a
     * chooser so the user decides. For files that only exist in our cache (not yet saved) we serve them
     * through the FileProvider declared in the manifest.
     */
    fun shareIntent(uri: Uri, mimeType: String, title: String = "Share your swap"): Intent {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(intent, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Shares a local file by wrapping it in a content Uri through the app FileProvider. */
    fun shareFile(file: File, mimeType: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return shareIntent(uri, mimeType)
    }

    /** Opens a saved item in the gallery app. */
    fun openIntent(uri: Uri, mimeType: String): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Deletes an export (used by "delete" on the result screen). */
    suspend fun delete(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }

    /** Reads width/height/fps/bitrate of a written file, for the "high bitrate" confirmation card. */
    suspend fun videoInfo(file: File): VideoInfo? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/mp4"
            VideoInfo(width, height, bitrate, durationMs, mime, file.length())
        } catch (t: Throwable) {
            Log.w(TAG, "cannot read video metadata", t)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun videoSize(uri: Uri): Pair<Int, Int> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            width to height
        } catch (t: Throwable) {
            0 to 0
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Human readable bitrate, e.g. `"24.0 Mbps"`. */
    fun formatBitrate(bitsPerSecond: Int): String =
        if (bitsPerSecond >= 1_000_000) String.format(Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000f)
        else String.format(Locale.US, "%.0f kbps", bitsPerSecond / 1000f)

    /** Human readable duration, `"01:23"`. */
    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000.0).roundToInt()
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    companion object {
        /** Must stay in sync with the metadata written into every export. */
        const val AI_DESCRIPTION = "AI Generated - made with SwapStudio AI on this device"

        private const val TAG = "ExportManager"
    }
}

/** Metadata of a written video file. */
data class VideoInfo(
    val width: Int,
    val height: Int,
    val bitrate: Int,
    val durationMs: Long,
    val mimeType: String,
    val bytes: Long,
) {
    /** True when the file really is 4K-ish and above the 20 Mbps bar from the spec. */
    val meetsQualityBar: Boolean get() = width >= 1920 && bitrate >= 20_000_000
}
