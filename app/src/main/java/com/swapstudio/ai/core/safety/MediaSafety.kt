package com.swapstudio.ai.core.safety

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.util.Log
import com.swapstudio.ai.core.inference.OrtEngine
import com.swapstudio.ai.core.inference.SessionSpec
import com.swapstudio.ai.core.models.ModelManager
import com.swapstudio.ai.core.util.BitmapUtils
import com.swapstudio.ai.core.util.MatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton

/** Verdict of the content check. */
sealed class ContentVerdict {
    /** Process this media. */
    data object Safe : ContentVerdict()

    /** Refuse: explicit content. */
    data class Blocked(val probability: Float, val reason: String) : ContentVerdict()

    /** Refuse: the user has not accepted the consent sheet. */
    data object ConsentMissing : ContentVerdict()

    /** Could not check (model not installed); the caller decides whether to allow it. */
    data class Unknown(val reason: String) : ContentVerdict()
}

/**
 * On-device content safety.
 *
 * Two independent gates:
 *  1. **Consent** – a mandatory checkbox that is stored in DataStore; no swap runs without it.
 *  2. **NSFW** – `open_nsfw.onnx` (Yahoo, 224x224, BGR, mean subtracted) run on the whole frame and on
 *     every detected face crop. The block threshold is 0.80, the same value FaceFusion uses.
 *
 * The check runs on the device only; no image ever leaves it. That is also why the app can honestly say
 * "no uploads" in the privacy section of the settings screen.
 */
@Singleton
class MediaSafety @Inject constructor(
    private val models: ModelManager,
) {
    @Volatile
    private var session: OrtSession? = null

    /** True when the analyser weights are installed. */
    fun isAvailable(): Boolean = models.nsfwFile().exists()

    /** Runs the NSFW classifier on a bitmap. */
    suspend fun checkImage(bitmap: Bitmap): ContentVerdict = withContext(Dispatchers.Default) {
        if (!isAvailable()) return@withContext ContentVerdict.Unknown("NSFW analyser not downloaded")
        val rgb = BitmapUtils.bitmapToRgb(bitmap)
        try {
            checkRgb(rgb)
        } finally {
            rgb.release()
        }
    }

    /** Runs the NSFW classifier on an RGB mat (whole frame). */
    fun checkRgb(rgb: Mat): ContentVerdict {
        if (!isAvailable()) return ContentVerdict.Unknown("NSFW analyser not downloaded")
        val probability = classify(rgb) ?: return ContentVerdict.Unknown("NSFW inference failed")
        return if (probability > THRESHOLD) {
            ContentVerdict.Blocked(probability, "Explicit content detected (${(probability * 100).toInt()}%). Pick another image.")
        } else {
            ContentVerdict.Safe
        }
    }

    /**
     * Classifies [rgb] and returns P(explicit) in 0..1, or null on failure.
     *
     * open_nsfw requires 224x224, BGR, minus the [104, 117, 123] mean (no scaling!).
     */
    fun classify(rgb: Mat): Float? {
        val resized = MatUtils.resize(rgb, 224, 224, Imgproc.INTER_AREA)
        return try {
            val input = MatUtils.toNchwFloat(
                resized,
                mean = floatArrayOf(104f, 117f, 123f),
                std = floatArrayOf(1f, 1f, 1f),
                scale = 1f,
                bgr = true,
            )
            val ortSession = ensureSession()
            val tensor = OnnxTensor.createTensor(
                OrtEngine.env(), FloatBuffer.wrap(input), longArrayOf(1, 3, 224, 224),
            )
            val output = ortSession.run(mapOf(ortSession.inputNames.first() to tensor)).use { result ->
                val value = result.get(0)
                check(value is OnnxTensor)
                OrtEngine.readFloats(value)
            }
            tensor.close()
            // Output is [1, 2] = [sfw, nsfw].
            when {
                output.size >= 2 -> output[1]
                output.size == 1 -> output[0]
                else -> null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "NSFW inference failed", t)
            null
        } finally {
            resized.release()
        }
    }

    /**
     * Checks a video by sampling [samples] evenly spaced frames. Returns the worst verdict.
     * Called before a long render so the user is not blocked after ten minutes of processing.
     */
    suspend fun checkVideoFrames(frames: List<Bitmap>): ContentVerdict = withContext(Dispatchers.Default) {
        if (!isAvailable()) return@withContext ContentVerdict.Unknown("NSFW analyser not downloaded")
        var worst: ContentVerdict = ContentVerdict.Safe
        for (frame in frames) {
            val rgb = BitmapUtils.bitmapToRgb(frame)
            val verdict = try {
                checkRgb(rgb)
            } finally {
                rgb.release()
            }
            if (verdict is ContentVerdict.Blocked) return@withContext verdict
            if (verdict is ContentVerdict.Unknown) worst = verdict
        }
        worst
    }

    @Synchronized
    private fun ensureSession(): OrtSession {
        session?.let { return it }
        val created = models.sessionFor(models.nsfwFile(), SessionSpec(threads = 2))
        session = created
        return created
    }

    /** Releases the cached session. */
    @Synchronized
    fun release() {
        session = null
    }

    companion object {
        private const val TAG = "MediaSafety"

        /** Anything above this P(explicit) blocks the media. */
        const val THRESHOLD = 0.80f
    }
}
