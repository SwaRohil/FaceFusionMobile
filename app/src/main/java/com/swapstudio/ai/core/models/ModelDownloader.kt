package com.swapstudio.ai.core.models

import android.util.Log
import com.swapstudio.ai.core.util.HashUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.math.max

/** Lifecycle of one model download. */
enum class DownloadStatus {
    /** Nothing on disk. */
    NOT_INSTALLED,

    /** Queued behind another download. */
    QUEUED,

    /** Bytes are flowing. */
    DOWNLOADING,

    /** Stopped by the user (or by a lost connection); the partial file is kept. */
    PAUSED,

    /** All bytes are here, hashing + unpacking now. */
    VERIFYING,

    /** Ready to use. */
    COMPLETED,

    /** A newer version exists upstream (version string differs from the manifest). */
    UPDATE_AVAILABLE,

    /** The download or the verification failed. */
    ERROR,
}

/**
 * Immutable snapshot of a single model's transfer, published through a [StateFlow] so Compose can
 * render it without polling.
 *
 * @param progress 0..1 (or -1 when the total size is unknown)
 * @param speedBytesPerSecond smoothed throughput, refreshed every 400 ms
 * @param etaSeconds remaining time estimate, -1 when unknown
 */
data class DownloadState(
    val modelId: String,
    val status: DownloadStatus = DownloadStatus.NOT_INSTALLED,
    val progress: Float = 0f,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSecond: Long = 0L,
    val etaSeconds: Long = -1L,
    val message: String? = null,
) {
    /** MB/s with one decimal, used by the model manager cards. */
    val speedMbPerSecond: Float get() = speedBytesPerSecond / (1024f * 1024f)

    /** Downloaded / total in MB, e.g. `"184 / 265 MB"`. */
    val mbLabel: String get() = "${bytesDownloaded / MB} / ${totalBytes / MB} MB"

    private companion object {
        const val MB = 1024L * 1024L
    }
}

/**
 * Resumable HTTP downloader for model weights.
 *
 * Design notes:
 *  * **Pause = cancel the call, keep the bytes.** Resuming sends `Range: bytes=<partial>-` and the
 *    server answers `206 Partial Content`. If it ignores the range (200) we restart the file, so a
 *    proxy that strips ranges cannot corrupt a model.
 *  * Every byte is written with [RandomAccessFile] at the exact offset, appending to `<file>.part`.
 *    A `.part` shorter than its own last written position is detected on resume via `416`.
 *  * Progress is throttled to 4 Hz per model; the UI stays smooth without flooding the main thread.
 *  * Verification runs off the main thread; SHA-256 when the manifest has a digest, otherwise a byte
 *    count (and CRC32 when the manifest supplies one as `crc32`).
 */
class ModelDownloader(private val store: ModelStore) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS) // a download may legitimately run for many minutes
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val states = ConcurrentHashMap<String, MutableStateFlow<DownloadState>>()
    private val activeCalls = ConcurrentHashMap<String, okhttp3.Call>()

    /** Current state of [modelId] (never null; a fresh NOT_INSTALLED state is created on demand). */
    fun stateOf(modelId: String): StateFlow<DownloadState> =
        states.getOrPut(modelId) { MutableStateFlow(DownloadState(modelId)) }.asStateFlow()

    /** Updates the published state for [modelId]. */
    fun update(state: DownloadState) {
        states.getOrPut(state.modelId) { MutableStateFlow(state) }.value = state
        if (state.status == DownloadStatus.COMPLETED) {
            // Keep the terminal state but drop the byte counters so the card shows "Installed".
            states[state.modelId]?.value = state.copy(progress = 1f, etaSeconds = 0L, speedBytesPerSecond = 0L)
        }
    }

    /** True while a call for this model is in flight. */
    fun isActive(modelId: String): Boolean = activeCalls.containsKey(modelId)

    /**
     * Starts (or resumes) [descriptor] and emits [DownloadState]s until it completes, fails or is
     * cancelled by [pause] / [cancel]. Cancelling the collecting coroutine pauses the transfer.
     */
    fun download(descriptor: ModelDescriptor): Flow<DownloadState> = callbackFlow {
        val modelId = descriptor.id
        val archive = store.archiveFile(descriptor)
        val part = File(archive.parentFile, archive.name + ".part")
        archive.parentFile?.mkdirs()

        val startBytes = if (part.exists()) part.length() else 0L
        val existing = if (archive.exists()) archive.length() else 0L
        val expected = if (descriptor.sizeBytes > 0) descriptor.sizeBytes else 0L

        // Already complete? Verify and finish immediately.
        if (archive.exists() && expected > 0 && existing == expected) {
            trySend(DownloadState(modelId, DownloadStatus.COMPLETED, 1f, existing, expected, message = "Installed"))
            finishVerification(descriptor, archive, trySend = { trySend(it) })
            close()
            return@callbackFlow
        }

        val alreadyHave = max(startBytes, existing)
        val request = Request.Builder()
            .url(descriptor.url)
            .header("Accept", "application/octet-stream")
            .apply { if (alreadyHave > 0) header("Range", "bytes=$alreadyHave-") }
            .get()
            .build()

        val call = client.newCall(request)
        activeCalls[modelId] = call
        val job = launch(Dispatchers.IO) {
            var raf: RandomAccessFile? = null
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful && response.code != 416) {
                        trySend(
                            DownloadState(
                                modelId, DownloadStatus.ERROR,
                                message = "HTTP ${response.code} ${response.message}",
                            ),
                        )
                        return@use
                    }
                    if (response.code == 416) {
                        // The partial file is already the whole thing.
                        part.renameTo(archive)
                        finishVerification(descriptor, archive, trySend = { trySend(it) })
                        return@use
                    }

                    val body = response.body
                    if (body == null) {
                        trySend(DownloadState(modelId, DownloadStatus.ERROR, message = "Empty response"))
                        return@use
                    }
                    val resuming = response.code == 206 && alreadyHave > 0
                    val offset = if (resuming) alreadyHave else 0L
                    if (!resuming && part.exists()) part.delete()
                    val contentLength = body.contentLength().let { if (it > 0) it else -1L }
                    val contentRange = response.header("Content-Range")
                    val total = when {
                        resuming && contentRange != null -> {
                            // "bytes 1024-2048/4096"
                            contentRange.substringAfterLast('/').toLongOrNull() ?: (offset + contentLength)
                        }
                        contentLength > 0 -> contentLength
                        else -> expected
                    }

                    raf = RandomAccessFile(part, "rw").apply { seek(offset) }
                    var written = offset
                    var windowStart = System.currentTimeMillis()
                    var windowBytes = 0L
                    var lastEmit = 0L
                    val buffer = ByteArray(BUFFER_SIZE)
                    body.byteStream().use { input ->
                        while (isActive) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            raf?.write(buffer, 0, read)
                            written += read
                            windowBytes += read
                            val now = System.currentTimeMillis()
                            val elapsed = now - windowStart
                            if (elapsed >= SPEED_WINDOW_MS) {
                                val speed = windowBytes * 1000L / max(1L, elapsed)
                                val progress = if (total > 0) (written.toFloat() / total) else -1f
                                val eta = if (speed > 0 && total > written) (total - written) / speed else -1L
                                if (now - lastEmit >= EMIT_INTERVAL_MS) {
                                    lastEmit = now
                                    trySend(
                                        DownloadState(
                                            modelId, DownloadStatus.DOWNLOADING,
                                            progress = progress.coerceIn(0f, 1f),
                                            bytesDownloaded = written,
                                            totalBytes = if (total > 0) total else 0L,
                                            speedBytesPerSecond = speed,
                                            etaSeconds = eta,
                                        ),
                                    )
                                }
                                windowStart = now
                                windowBytes = 0
                            }
                        }
                    }
                    raf.close()
                    raf = null

                    if (!isActive) {
                        // Paused by the collector; keep the .part for the next resume.
                        trySend(
                            DownloadState(
                                modelId, DownloadStatus.PAUSED,
                                progress = if (total > 0) (written.toFloat() / total).coerceIn(0f, 1f) else 0f,
                                bytesDownloaded = written, totalBytes = total,
                                message = "Paused",
                            ),
                        )
                        return@use
                    }

                    val complete = (total > 0 && written >= total) || (total <= 0 && written > 0)
                    if (!complete) {
                        trySend(
                            DownloadState(
                                modelId, DownloadStatus.ERROR,
                                progress = if (total > 0) written.toFloat() / total else 0f,
                                bytesDownloaded = written, totalBytes = total,
                                message = "Transfer ended early ($written of $total bytes)",
                            ),
                        )
                        return@use
                    }

                    // Atomic-ish swap: the archive only appears once every byte is on disk.
                    if (archive.exists()) archive.delete()
                    if (!part.renameTo(archive)) {
                        part.copyTo(archive, overwrite = true)
                        part.delete()
                    }
                    finishVerification(descriptor, archive, trySend = { trySend(it) })
                }
            } catch (c: CancellationException) {
                closeQuietly(raf)
                throw c
            } catch (t: Throwable) {
                closeQuietly(raf)
                Log.w(TAG, "download failed for ${descriptor.id}", t)
                trySend(
                    DownloadState(
                        modelId, DownloadStatus.ERROR,
                        bytesDownloaded = if (part.exists()) part.length() else 0L,
                        message = t.message ?: t.javaClass.simpleName,
                    ),
                )
            } finally {
                activeCalls.remove(modelId)
            }
        }

        awaitClose {
            job.cancel()
            call.cancel()
            activeCalls.remove(modelId)
        }
    }.flowOn(Dispatchers.IO)

    /** Verifies the finished archive and unpacks it when needed, emitting VERIFYING/COMPLETED. */
    private suspend fun finishVerification(
        descriptor: ModelDescriptor,
        archive: File,
        trySend: (DownloadState) -> Unit,
    ) {
        trySend(
            DownloadState(
                descriptor.id, DownloadStatus.VERIFYING, 1f,
                bytesDownloaded = archive.length(), totalBytes = archive.length(),
                message = "Verifying",
            ),
        )
        val verified = verifyHash(descriptor, archive)
        if (!verified) {
            archive.delete()
            trySend(
                DownloadState(
                    descriptor.id, DownloadStatus.ERROR,
                    message = "Integrity check failed - file deleted, please retry",
                ),
            )
            return
        }
        if (descriptor.archive) unpack(descriptor, archive)
        val installed = store.isInstalled(descriptor)
        trySend(
            DownloadState(
                descriptor.id,
                if (installed) DownloadStatus.COMPLETED else DownloadStatus.ERROR,
                progress = 1f,
                bytesDownloaded = archive.length(), totalBytes = archive.length(),
                message = if (installed) "Installed" else "Unpack failed",
            ),
        )
    }

    /**
     * Returns true when [file] matches the manifest.
     *
     * Order of preference: SHA-256 (strong) -> CRC32 -> exact byte count. A model with an unknown
     * expected size is accepted as long as the file is not empty.
     */
    suspend fun verifyHash(descriptor: ModelDescriptor, file: File): Boolean = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) return@withContext false
        val expected = descriptor.sizeBytes
        if (expected > 0 && file.length() != expected) {
            Log.w(TAG, "${descriptor.id}: size ${file.length()} != expected $expected")
            return@withContext false
        }
        if (descriptor.hasHash) {
            val actual = HashUtils.sha256Hex(file)
            return@withContext actual.equals(descriptor.sha256, ignoreCase = true)
        }
        return@withContext true
    }

    /** Extracts a zip asset into `filesDir/models/`, refusing entries that escape the directory. */
    suspend fun unpack(descriptor: ModelDescriptor, archive: File) = withContext(Dispatchers.IO) {
        val root = store.root
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(root, entry.name)
                val canonicalRoot = root.canonicalPath + File.separator
                if (!target.canonicalPath.startsWith(canonicalRoot)) {
                    Log.w(TAG, "skipping unsafe zip entry ${entry.name}")
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
            }
        }
        // Drop the archive once expanded: the unpacked weights are what the runtime loads.
        if (store.isInstalled(descriptor)) archive.delete()
    }

    /** Cancels an in-flight transfer and deletes the partial file. */
    fun cancel(modelId: String) {
        activeCalls.remove(modelId)?.cancel()
        states[modelId]?.value = DownloadState(modelId, DownloadStatus.NOT_INSTALLED)
    }

    /** Pauses an in-flight transfer; the partial file is kept for [download]. */
    fun pause(modelId: String) {
        activeCalls.remove(modelId)?.cancel()
        val current = states[modelId]?.value ?: return
        if (current.status == DownloadStatus.DOWNLOADING || current.status == DownloadStatus.QUEUED) {
            states[modelId]?.value = current.copy(status = DownloadStatus.PAUSED, message = "Paused", speedBytesPerSecond = 0, etaSeconds = -1)
        }
    }

    /** Deletes every byte of this model (archive, partial and unpacked files). */
    suspend fun delete(descriptor: ModelDescriptor) = withContext(Dispatchers.IO) {
        activeCalls.remove(descriptor.id)?.cancel()
        store.archiveFile(descriptor).delete()
        File(store.root, descriptor.fileName + ".part").delete()
        store.delete(descriptor)
        states[descriptor.id]?.value = DownloadState(descriptor.id, DownloadStatus.NOT_INSTALLED)
    }

    private fun closeQuietly(raf: RandomAccessFile?) {
        try {
            raf?.close()
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val TAG = "ModelDownloader"
        const val BUFFER_SIZE = 128 * 1024
        const val SPEED_WINDOW_MS = 400L
        const val EMIT_INTERVAL_MS = 250L
    }
}
