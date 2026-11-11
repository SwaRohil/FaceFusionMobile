package com.swapstudio.ai.core.swap

import kotlin.math.abs

/**
 * Fixed-window EMA for the 52 blendshape coefficients of one face in a video.
 *
 * `cur = prev * (1 - alpha) + new * alpha` with `alpha = 0.15`, i.e. **0.85 previous + 0.15 new**, and a
 * history window of five frames. This is the value the app was tuned for:
 *  * a blink lasts 3-6 frames at 30 fps, so an 0.85 EMA keeps ~40 % of the blink amplitude on the peak
 *    frame: the eye visibly closes (no frozen face) but the lid does not chatter;
 *  * a talking mouth (2-4 Hz) keeps its full range because the filter only lags by ~2 frames;
 *  * the "is the expression frozen" test lets the video screen warn the user when a clip holds a static
 *    face for too long (some source clips are literally stills).
 */
class BlendshapeEma(
    private val alpha: Double = 0.15,
    private val window: Int = 5,
    private val count: Int = 52,
) {
    private val history = ArrayDeque<FloatArray>()
    private var state = FloatArray(count)

    /** True once at least one frame has been pushed. */
    val isWarm: Boolean get() = history.isNotEmpty()

    /** Emits the smoothed coefficients for [input]. The returned array is a fresh copy. */
    fun push(input: FloatArray): FloatArray {
        if (state.size != input.size) state = FloatArray(input.size)
        if (history.isEmpty()) {
            input.copyInto(state)
        } else {
            for (i in state.indices) {
                state[i] = (state[i] * (1.0 - alpha) + input[i] * alpha).toFloat()
            }
        }
        history.addLast(input.copyOf())
        while (history.size > window) history.removeFirst()
        return state.copyOf()
    }

    /** Mean of the current window, used for diagnostics. */
    fun windowMean(): FloatArray {
        if (history.isEmpty()) return FloatArray(state.size)
        val out = FloatArray(state.size)
        for (frame in history) for (i in out.indices) out[i] += frame.getOrElse(i) { 0f }
        for (i in out.indices) out[i] /= history.size
        return out
    }

    /** Largest change of any coefficient over the whole window (0 = completely static expression). */
    fun motion(epsilon: Float = 1e-4f): Float {
        if (history.size < 2) return 0f
        var maxDelta = 0f
        val first = history.first()
        for (frame in history) {
            for (i in frame.indices) {
                val delta = abs(frame[i] - first.getOrElse(i) { 0f })
                if (delta > maxDelta) maxDelta = delta
            }
        }
        return if (maxDelta < epsilon) 0f else maxDelta
    }

    /** True when the expression has not changed at all for the whole window (frozen face). */
    fun isFrozen(): Boolean = history.size >= window && motion() == 0f

    /** Exponential value for the next step, exposed for tests. */
    fun next(previous: Float, measurement: Float): Float =
        (previous * (1.0 - alpha) + measurement * alpha).toFloat()

    fun reset() {
        history.clear()
        state = FloatArray(count)
    }
}
