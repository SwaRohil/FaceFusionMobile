package com.swapstudio.ai.core.swap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlendshapeEmaTest {

    @Test
    fun `first push seeds the state without smoothing`() {
        val ema = BlendshapeEma(count = 3)
        val out = ema.push(floatArrayOf(0.2f, 0.4f, 0.6f))
        assertEquals(0.2f, out[0], 1e-6f)
        assertEquals(0.4f, out[1], 1e-6f)
        assertEquals(0.6f, out[2], 1e-6f)
        assertTrue(ema.isWarm)
    }

    @Test
    fun `state follows 0_85 previous + 0_15 new`() {
        val ema = BlendshapeEma(alpha = 0.15, count = 1)
        ema.push(floatArrayOf(0f))
        val out = ema.push(floatArrayOf(1f))
        assertEquals(0.15f, out[0], 1e-6f)
        val next = ema.push(floatArrayOf(1f))
        assertEquals(0.15f + 0.85f * 0.15f, next[0], 1e-6f)
    }

    @Test
    fun `push returns a copy so callers cannot mutate internal state`() {
        val ema = BlendshapeEma(count = 2)
        val out = ema.push(floatArrayOf(1f, 1f))
        out[0] = 0f
        assertEquals(1f, ema.push(floatArrayOf(1f, 1f))[0], 1e-6f)
    }

    @Test
    fun `a static expression is reported as frozen once the window filled`() {
        val ema = BlendshapeEma(window = 5, count = 2)
        repeat(4) { ema.push(floatArrayOf(0.3f, 0.7f)) }
        assertFalse("window not full yet", ema.isFrozen())
        ema.push(floatArrayOf(0.3f, 0.7f))
        assertTrue(ema.isFrozen())
        assertEquals(0f, ema.motion(), 1e-9f)
    }

    @Test
    fun `a blink is not frozen and keeps most of its amplitude`() {
        val ema = BlendshapeEma(window = 5, count = 1)
        repeat(4) { ema.push(floatArrayOf(0f)) }
        val peak = ema.push(floatArrayOf(1f))[0]
        assertFalse(ema.isFrozen())
        // The first frame after a rest period only reaches 0.15. A natural blink lasts 3-6 frames;
        // by the sixth frame the filter must be above half so the eye visibly reads as closed.
        assertEquals(0.15f, peak, 1e-6f)
        var value = peak
        repeat(5) { value = ema.push(floatArrayOf(1f))[0] }
        // 1 - 0.85^6 = 0.623
        assertEquals(0.6229f, value, 2e-3f)
        assertTrue("blink peak $value should exceed 0.5", value > 0.5f)
    }

    @Test
    fun `reset clears history and state`() {
        val ema = BlendshapeEma(count = 2)
        ema.push(floatArrayOf(1f, 1f))
        ema.reset()
        assertFalse(ema.isWarm)
        assertEquals(0f, ema.push(floatArrayOf(0f, 0f))[0], 1e-6f)
    }

    @Test
    fun `windowMean averages the retained frames`() {
        val ema = BlendshapeEma(window = 2, count = 1)
        ema.push(floatArrayOf(0f))
        ema.push(floatArrayOf(1f))
        assertEquals(0.5f, ema.windowMean()[0], 1e-6f)
    }

    @Test
    fun `output size adapts when MediaPipe returns a different coefficient count`() {
        val ema = BlendshapeEma(count = 52)
        val out = ema.push(FloatArray(61) { 0.5f })
        assertEquals(61, out.size)
        assertEquals(0.5f, out[60], 1e-6f)
    }
}
