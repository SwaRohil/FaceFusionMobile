package com.swapstudio.ai.core.inference

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet

/** Which execution provider a session should try to use. */
enum class EpKind {
    /** Pure CPU. The most compatible path and the fallback for every other provider. */
    CPU,

    /** Android Neural Networks API: reaches any vendor accelerator (Adreno + Hexagon on Snapdragon). */
    NNAPI,

    /** Qualcomm QNN: direct Hexagon HTP (NPU) / Adreno GPU access, needs onnxruntime-android-qnn. */
    QNN,
}

/**
 * Everything needed to build one inference session.
 *
 * @param threads intra-op thread count. On a Snapdragon 8 Elite class chip use 4 (the prime + 3
 *   performance cores); using all 8 hurts thermals for long videos.
 * @param kind execution provider.
 * @param fp16 ask the accelerator to run in float16 (QNN `enable_htp_fp16_precision`, NNAPI
 *   `USE_FP16`). All models used by this app are FP16 so this is always preferable.
 * @param htpPerformanceMode QNN HTP DVFS mode; see [HtpPerformanceMode].
 * @param socModel optional QNN SoC id override (`"87"` for SM8750 / Snapdragon 8 Elite). Leave null
 *   to let QNN detect it.
 * @param enableNhwc lay out tensors NHWC on the HTP (much faster for vision models).
 */
data class SessionSpec(
    val threads: Int = 4,
    val kind: EpKind = EpKind.CPU,
    val fp16: Boolean = true,
    val htpPerformanceMode: String = HtpPerformanceMode.SUSTAINED_HIGH_PERFORMANCE,
    val socModel: String? = null,
    val enableNhwc: Boolean = true,
    val optimizationLevel: OrtSession.SessionOptions.OptLevel = OrtSession.SessionOptions.OptLevel.ALL_OPT,
)

/** QNN HTP performance modes accepted by `htp_performance_mode`. */
object HtpPerformanceMode {
    const val BURST = "burst"
    const val SUSTAINED_HIGH_PERFORMANCE = "sustained_high_performance"
    const val HIGH_PERFORMANCE = "high_performance"
    const val BALANCED = "balanced"
    const val LOW_POWER_SAVER = "low_power_saver"
    const val POWER_SAVER = "power_saver"
}

/**
 * Thin wrapper around the ONNX Runtime Java API.
 *
 * The environment is process wide and created once; sessions are cheap to recreate and expensive to
 * keep, so each pipeline owns its sessions and closes them when the screen goes away.
 */
object OrtEngine {

    @Volatile
    private var environment: OrtEnvironment? = null

    /** Returns the shared [OrtEnvironment], creating it on first use. */
    fun env(): OrtEnvironment {
        environment?.let { return it }
        synchronized(this) {
            environment?.let { return it }
            val created = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING)
            environment = created
            return created
        }
    }

    /**
     * True when the QNN flavour of ONNX Runtime is on the classpath *and* its backend libraries were
     * extracted to [nativeLibraryDir] (they only exist for arm64-v8a).
     */
    fun qnnAvailable(nativeLibraryDir: String?): Boolean {
        if (nativeLibraryDir == null) return false
        val dir = File(nativeLibraryDir)
        val hasHtp = File(dir, "libQnnHtp.so").exists() || File(dir, "libQnnHtpV73Skel.so").exists()
        val hasProvider = File(dir, "libonnxruntime4j_jni.so").exists() || File(dir, "libonnxruntime.so").exists()
        return hasHtp && hasProvider && SYSTEM_ARCH == "aarch64"
    }

    /** True when the runtime exposes the NNAPI provider (it always does in the plain AAR). */
    fun nnapiAvailable(): Boolean = !qnnAvailable(null) || SYSTEM_ARCH == "aarch64"

    private val SYSTEM_ARCH: String = System.getProperty("os.arch") ?: ""

    /**
     * Creates a session for [modelPath].
     *
     * Provider selection is best effort: when the requested accelerator cannot be initialised (no
     * NPU, missing library, unsupported operator) we degrade to the next best option instead of
     * failing the whole swap.
     */
    fun createSession(modelPath: String, spec: SessionSpec): OrtSession {
        val env = env()
        val options = OrtSession.SessionOptions()
        options.setOptimizationLevel(spec.optimizationLevel)
        options.setIntraOpNumThreads(spec.threads.coerceIn(1, 8))
        options.setInterOpNumThreads(1)
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        options.setMemoryPatternOptimization(true)

        when (spec.kind) {
            EpKind.QNN -> {
                if (!tryAddQnn(options, spec)) tryAddNnapi(options, spec)
            }
            EpKind.NNAPI -> {
                if (!tryAddNnapi(options, spec) && spec.kind == EpKind.CPU) {
                    // nothing to do, CPU is the implicit fallback
                }
            }
            EpKind.CPU -> Unit
        }

        return try {
            env.createSession(modelPath, options)
        } catch (t: Throwable) {
            // A provider that accepted registration can still fail at session creation time
            // (unsupported op set, NPU out of memory, ...). Rebuild on the CPU and move on.
            runCatching { options.close() }
            val cpuOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(spec.optimizationLevel)
                setIntraOpNumThreads(spec.threads.coerceIn(1, 8))
                setInterOpNumThreads(1)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            }
            env.createSession(modelPath, cpuOptions)
        }
    }

    private fun tryAddQnn(options: OrtSession.SessionOptions, spec: SessionSpec): Boolean {
        val providerOptions = linkedMapOf(
            "backend_path" to "libQnnHtp.so",
            "htp_performance_mode" to spec.htpPerformanceMode,
            "enable_htp_fp16_precision" to if (spec.fp16) "1" else "0",
            "enable_htp_fp16_math" to if (spec.fp16) "1" else "0",
            "offload_graph_io_quantization" to "0",
        )
        if (spec.enableNhwc) {
            providerOptions["enable_nhwc"] = "1"
            providerOptions["enable_htp_shared_memory_allocator"] = "1"
        }
        spec.socModel?.let { providerOptions["soc_model"] = it }
        return try {
            options.addQnn(providerOptions)
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun tryAddNnapi(options: OrtSession.SessionOptions, spec: SessionSpec): Boolean = try {
        val flags = EnumSet.of(NNAPIFlags.USE_FP16, NNAPIFlags.CPU_DISABLED)
        options.addNnapi(flags)
        true
    } catch (t: Throwable) {
        // Older devices: NNAPI with float16 may be unsupported, retry without flags.
        runCatching { options.addNnapi() }.isSuccess
    }

    // -----------------------------------------------------------------------------------------
    // Tensor helpers
    // -----------------------------------------------------------------------------------------

    /** Copies a float [OnnxTensor] into a plain array (native -> JVM, one memcpy). */
    fun readFloats(tensor: OnnxTensor): FloatArray {
        val buffer: ByteBuffer = tensor.getByteBuffer()
        val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val out = FloatArray(floats.remaining())
        floats.get(out)
        return out
    }

    /** Copies an int64 [OnnxTensor] into a plain array. */
    fun readLongs(tensor: OnnxTensor): LongArray {
        val buffer: ByteBuffer = tensor.getByteBuffer()
        val longs = buffer.order(ByteOrder.nativeOrder()).asLongBuffer()
        val out = LongArray(longs.remaining())
        longs.get(out)
        return out
    }

    /** Shape of a session input/output, or `-1` for symbolic dimensions. */
    fun shapeOf(info: NodeInfo): LongArray {
        val tensor = info.info as? TensorInfo ?: return longArrayOf()
        return tensor.shape
    }

    /** Name of the first input/output of a session (models in this app all have one). */
    fun singleInputName(session: OrtSession): String = session.inputNames.first()
    fun singleOutputName(session: OrtSession): String = session.outputNames.first()

    /** Runs a single-input/single-output model and returns the first output as floats. */
    fun runSingle(session: OrtSession, inputName: String, tensor: OnnxTensor): FloatArray {
        session.run(mapOf(inputName to tensor)).use { result ->
            val value = result.get(0)
            check(value is OnnxTensor) { "expected a tensor output" }
            return readFloats(value)
        }
    }

    /** Safe close. */
    fun closeQuietly(session: OrtSession?) {
        try {
            session?.close()
        } catch (_: OrtException) {
        }
    }
}
