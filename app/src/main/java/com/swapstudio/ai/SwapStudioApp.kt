package com.swapstudio.ai

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.swapstudio.ai.core.background.SwapForegroundService
import com.swapstudio.ai.core.inference.OrtEngine
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader
import java.io.File
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Responsibilities:
 *  * Hilt root (`@HiltAndroidApp`).
 *  * Initialise OpenCV, preferring the **native library bundled in the APK** (`libopencv_java4.so`).
 *    The classic `OpenCVLoader.initDebug()` path only works for the SDK style distribution; the Maven
 *    AAR ships the .so already, so a plain `System.loadLibrary` is enough and much faster.
 *  * Optionally point ONNX Runtime at the QNN backend directory.
 *  * Provide the WorkManager configuration (needed because we inject a worker factory).
 */
@HiltAndroidApp
class SwapStudioApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        SwapForegroundService.createChannels(this)
        initialiseOpenCv()
        prepareQnn()
        trimCaches()
    }

    private fun initialiseOpenCv() {
        try {
            System.loadLibrary("opencv_java4")
            Log.i(TAG, "OpenCV loaded from the APK")
        } catch (t: Throwable) {
            // Some OpenCV AARs name the library differently or need the loader to unpack it.
            val loaded = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
            if (!loaded) {
                val legacy = runCatching { OpenCVLoader.initDebug() }.getOrDefault(false)
                Log.w(TAG, "OpenCV initLocal=$loaded initDebug=$legacy", t)
            }
        }
    }

    /**
     * ONNX Runtime's QNN provider looks for `libQnnHtp.so` in the loader search path. The AAR installs
     * the libraries next to `libonnxruntime.so`, which is already on the path; this only logs what is
     * actually present so a missing backend is visible in logcat instead of failing silently.
     */
    private fun prepareQnn() {
        val dir = File(applicationInfo.nativeLibraryDir)
        val hasHtp = File(dir, "libQnnHtp.so").exists()
        val hasCpuBackend = File(dir, "libQnnCpu.so").exists()
        Log.i(TAG, "QNN backends: htp=$hasHtp cpu=$hasCpuBackend arch=${System.getProperty("os.arch")}")
        OrtEngine.env() // warm the environment up on a background-free code path
    }

    /** Deletes old scratch files so a 4K render always has room. */
    private fun trimCaches() {
        runCatching {
            val cutoff = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) file.delete()
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.WARN)
            .build()

    private companion object {
        const val TAG = "SwapStudioApp"
    }
}
