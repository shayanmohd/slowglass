package com.mohdshayan.slowglass.core

import com.mohdshayan.slowglass.core.stack.BlendMath
import com.mohdshayan.slowglass.core.stack.MeanTree
import com.mohdshayan.slowglass.core.stack.MeanTreeSchedule
import com.mohdshayan.slowglass.core.stack.SubExposureCounter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StackMathTest {

    @Test
    fun meanTreeOver100kFramesStaysWithin1e4OfTheTrueMean() {
        val rnd = Random(7)
        val tree = MeanTree(3)
        val sums = DoubleArray(3)
        val n = 100_000
        repeat(n) { i ->
            // A slow drift plus noise, so the late frames differ from the early ones.
            val base = 0.2f + 0.6f * i / n
            val f = FloatArray(3) { c -> (base + rnd.nextFloat() * 0.2f - 0.1f + c * 0.05f).coerceIn(0f, 1f) }
            for (c in 0 until 3) sums[c] += f[c].toDouble()
            tree.add(f)
        }
        val mean = tree.resolve()
        for (c in 0 until 3) assertEquals(sums[c] / n, mean[c].toDouble(), 1e-4)
    }

    @Test
    fun meanTreeIsExactForCountsThatAreNotMultiplesOf64() {
        for (n in listOf(1, 63, 65, 4097, 4161)) {
            val tree = MeanTree(1)
            var sum = 0.0
            for (i in 0 until n) {
                val v = (i % 10) / 10f
                sum += v
                tree.add(floatArrayOf(v))
            }
            assertEquals("n=$n", sum / n, tree.resolve()[0].toDouble(), 1e-5)
        }
    }

    @Test
    fun scheduleNeverUsesAWeightBelowOneOver64UntilManySuperblocks() {
        val s = MeanTreeSchedule()
        repeat(64 * 64 * 3) { s.onFrame() }
        assertEquals(3L, s.superblocks)
        assertEquals(1f / 3f, s.accFoldWeight(), 1e-6f)
        val w = s.resolveWeights()
        assertEquals(1f, w[0], 1e-6f)
        assertEquals(0f + 4096f / (4096f * 3), w[1], 1e-6f)
    }

    @Test
    fun eightBitPathKeepsTheMeanWhereScaledSumsLoseIt() {
        val rnd = Random(11)
        // An 8-bit framebuffer with random dither: unbiased rounding to 1/255.
        val eightBit: (Float) -> Float = { v -> (Math.floor(v * 255.0 + rnd.nextDouble()) / 255.0).toFloat().coerceIn(0f, 1f) }
        val px = 256
        for (n in listOf(1, 78, 700, 5_000, 9_000)) {
            val running = MeanTree(px, running = true, quantize = eightBit)
            val sums = MeanTree(px, quantize = eightBit)
            val exact = DoubleArray(px)
            repeat(n) { i ->
                // Water: a pixel that changes every frame around a slowly drifting level.
                val f = FloatArray(px) { p -> (0.25f + 0.4f * p / px + 0.1f * i / n + rnd.nextFloat() * 0.2f).coerceIn(0f, 1f) }
                for (p in 0 until px) exact[p] += f[p].toDouble() / n
                running.add(f)
                sums.add(f)
            }
            // Root-mean-square error across the pixels, in 8-bit levels: the grain a viewer sees.
            fun rms(v: FloatArray) = kotlin.math.sqrt((0 until px).sumOf { (v[it] - exact[it]).let { d -> d * d } } / px) * 255
            val r = running.resolve()
            assertTrue("n=$n running mean grain ${rms(r)} levels", rms(r) < 2.0)
            if (n == 78) {
                // The half-float schedule in an 8-bit buffer: a partial superblock holds the mean / 64
                // in a few levels, and resolving multiplies the rounding by 52. This was the bug.
                assertTrue("sums grain only ${rms(sums.resolve())}", rms(sums.resolve()) > 8.0)
            }
        }
    }

    @Test
    fun runningWeightsMatchTheSchedule() {
        val s = MeanTreeSchedule()
        repeat(64 * 64 + 64 * 3 + 5) { s.onFrame() }
        val w = s.runningResolveWeights()
        val n = (64 * 64 + 64 * 3 + 5).toFloat()
        assertEquals(4096f / n, w[0], 1e-6f)
        assertEquals(192f / n, w[1], 1e-6f)
        assertEquals(5f / n, w[2], 1e-6f)
        assertEquals(1f, w[0] + w[1] + w[2], 1e-6f)
        assertEquals(1f / 5f, s.runningAddWeight, 0f)
        assertEquals(1f / 3f, s.runningBlockWeight, 0f)
    }

    @Test
    fun lightenIsPerChannelMax() {
        val dst = floatArrayOf(0.1f, 0.9f, 0.5f, 0f)
        BlendMath.lighten(dst, floatArrayOf(0.4f, 0.2f, 0.5f, 1f))
        assertArrayEquals(floatArrayOf(0.4f, 0.9f, 0.5f, 1f), dst, 0f)
    }

    @Test
    fun motionResolveKeepsLampsAndDissolvesDarkMovers() {
        // A dim moving figure: max is dark, so the mean wins.
        assertEquals(0.2f, BlendMath.motionResolve(0.2f, 0.5f, 0.5f, 0.5f), 1e-6f)
        // A street lamp: max luma 1.0, keep 1.0 gives the max back.
        assertEquals(0.95f, BlendMath.motionResolve(0.4f, 0.95f, 1.0f, 1.0f), 1e-6f)
        // Keep lights at the default half lands halfway.
        assertEquals(0.675f, BlendMath.motionResolve(0.4f, 0.95f, 1.0f, 0.5f), 1e-6f)
    }

    @Test
    fun subExposureCounterCompletesEveryNFrames() {
        val c = SubExposureCounter(3)
        val done = (1..9).map { c.onFrame() }
        assertEquals(listOf(false, false, true, false, false, true, false, false, true), done)
        assertEquals(3L, c.completedSubs)
        assertFalse(SubExposureCounter(1).let { it.inSub != 0 })
        assertTrue(SubExposureCounter(0).onFrame())
    }
}
