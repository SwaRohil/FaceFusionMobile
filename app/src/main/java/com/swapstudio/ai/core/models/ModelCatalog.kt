package com.swapstudio.ai.core.models

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.swapstudio.ai.core.util.HashUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One downloadable model described by `assets/models.json`.
 *
 * @property id stable key used by the model manager and the pipelines (`"face_swap"`, ...)
 * @property fileName name on disk inside `filesDir/models/`
 * @property url direct HTTPS link (GitHub release asset, no auth needed)
 * @property sizeMb human readable size, shown before the download starts
 * @property sha256 lower-case hex digest, empty when the publisher does not provide one
 * @property version bumped when the upstream weights change
 * @property required when true the app cannot swap anything without this file
 * @property archive when true the asset is a zip that is expanded after verification
 * @property unpacked list of files extracted from the archive (relative paths)
 */
data class ModelDescriptor(
    val id: String,
    val name: String,
    val fileName: String,
    val url: String,
    val sizeMb: Long,
    val sha256: String,
    val version: String,
    val required: Boolean,
    val archive: Boolean = false,
    val unpacked: List<String> = emptyList(),
    val description: String = "",
) {
    /** Bytes expected on disk (for the archive itself, not its contents). */
    val sizeBytes: Long get() = sizeMb * 1024L * 1024L

    /** True when a digest is available and can be verified after download. */
    val hasHash: Boolean get() = sha256.length == 64 && sha256.all { it.isDigit() || it in 'a'..'f' }

    companion object {
        /**
         * Turns one `models.json` entry into a descriptor, tolerating both the canonical form
         * (`"file_name"`, `"size_mb"`, ...) and camelCase keys.
         */
        fun fromJson(entry: org.json.JSONObject): ModelDescriptor {
            fun str(vararg keys: String): String {
                for (key in keys) if (entry.has(key)) return entry.optString(key)
                return ""
            }
            fun long(vararg keys: String): Long {
                for (key in keys) if (entry.has(key)) return entry.optLong(key)
                return 0L
            }
            val unpacked = mutableListOf<String>()
            entry.optJSONArray("unpacked")?.let { array ->
                for (i in 0 until array.length()) unpacked += array.getString(i)
            }
            return ModelDescriptor(
                id = str("id"),
                name = str("name"),
                fileName = str("file_name", "fileName"),
                url = str("url"),
                sizeMb = long("size_mb", "sizeMb"),
                sha256 = str("sha256").lowercase(),
                version = str("version"),
                required = entry.optBoolean("required", false),
                archive = entry.optBoolean("archive", false),
                unpacked = unpacked,
                description = str("description"),
            )
        }
    }
}

/**
 * Loads and validates `assets/models.json`.
 *
 * The manifest is deliberately data-only: ships no weights, only URLs, sizes and digests, so the
 * APK stays small and the model manager can pause/resume downloads.
 */
class ModelCatalog(private val context: Context) {

    /** All descriptors, in manifest order (required models first so the UI reads naturally). */
    suspend fun load(): List<ModelDescriptor> = withContext(Dispatchers.IO) {
        val json = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val array = org.json.JSONObject(json).getJSONArray("models")
        val models = ArrayList<ModelDescriptor>(array.length())
        for (i in 0 until array.length()) {
            val descriptor = ModelDescriptor.fromJson(array.getJSONObject(i))
            if (descriptor.id.isNotEmpty() && descriptor.url.isNotEmpty()) models += descriptor
        }
        models
    }

    companion object {
        const val ASSET_NAME = "models.json"
    }
}

/**
 * Filesystem layout of downloaded models.
 *
 * ```
 * filesDir/models/
 *   buffalo_l/det_10g.onnx
 *   buffalo_l/w600k_r50.onnx
 *   inswapper_128_fp16.onnx
 *   gfpgan_1.4.onnx
 *   real_esrgan_x2_fp16.onnx
 *   bisenet_resnet_34.onnx
 * ```
 */
class ModelStore(private val context: Context) {

    val root: File get() = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    /** The archive as downloaded, before unpacking. */
    fun archiveFile(descriptor: ModelDescriptor): File = File(root, descriptor.fileName)

    /** Resolves a model file that may live inside an unpacked archive folder. */
    fun resolve(fileName: String): File {
        val direct = File(root, fileName)
        if (direct.exists()) return direct
        // Look one level deep (buffalo_l/...), covers the unpacked InsightFace pack.
        root.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val nested = File(child, fileName)
                if (nested.exists()) return nested
            }
        }
        return direct
    }

    /** True when every file of this descriptor is on disk. */
    fun isInstalled(descriptor: ModelDescriptor): Boolean {
        if (descriptor.archive) {
            if (descriptor.unpacked.isEmpty()) return archiveFile(descriptor).exists()
            return descriptor.unpacked.all { resolve(it).exists() }
        }
        return archiveFile(descriptor).exists()
    }

    /** Bytes of this model currently on disk (archive + unpacked files). */
    fun installedBytes(descriptor: ModelDescriptor): Long {
        var total = 0L
        val archive = archiveFile(descriptor)
        if (archive.exists()) total += archive.length()
        descriptor.unpacked.forEach { name ->
            val file = resolve(name)
            if (file.exists()) total += file.length()
        }
        return total
    }

    /** Total bytes used by the whole model directory. */
    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Free bytes on the data partition. */
    fun freeBytes(): Long = android.os.StatFs(root.absolutePath).availableBytes

    /**
     * Deletes a model, including unpacked files and (for archives) the expanded folder.
     * Returns true when nothing of this model is left on disk.
     */
    fun delete(descriptor: ModelDescriptor): Boolean {
        archiveFile(descriptor).delete()
        descriptor.unpacked.forEach { resolve(it).delete() }
        // Remove now empty archive folders (buffalo_l/).
        root.listFiles()?.filter { it.isDirectory && it.listFiles().isNullOrEmpty() }?.forEach { it.delete() }
        return !isInstalled(descriptor)
    }

    /** SHA-256 of a file, or null when it does not exist. Runs on IO. */
    suspend fun sha256(file: File): String? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        HashUtils.sha256Hex(file)
    }

    /** CRC32 of a file as 8 lower-case hex chars (FaceFusion compatible). */
    suspend fun crc32(file: File): String? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        HashUtils.crc32Hex(file)
    }

    /** Cheap sanity check used during development and unit tests. */
    @VisibleForTesting
    fun listFiles(): List<String> = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.toList()

    /** Parses the manifest JSON directly (exposed for unit tests). */
    @VisibleForTesting
    fun parseManifest(json: String): List<ModelDescriptor> {
        val array = org.json.JSONObject(json).getJSONArray("models")
        return (0 until array.length()).map { ModelDescriptor.fromJson(array.getJSONObject(it)) }
    }
}
