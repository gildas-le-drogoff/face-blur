// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.domain
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
class PixelEffectsTest {
    private fun packArgb(alpha: Int, red: Int, green: Int, blue: Int) =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    private fun extractRed(pixel: Int) = (pixel shr 16) and 0xFF
    @Test
    fun `gaussian blur leaves a uniform image unchanged`() {
        val color = packArgb(255, 10, 120, 240)
        val pixels = IntArray(30 * 20) { color }
        PixelEffects.gaussianBlur(pixels, 30, 20, sigma = 5f)
        assertTrue(pixels.all { it == color })
    }
    @Test
    fun `gaussian blur spreads an impulse symmetrically and keeps opacity`() {
        val width = 41
        val height = 41
        val pixels = IntArray(width * height) { packArgb(255, 0, 0, 0) }
        pixels[20 * width + 20] = packArgb(255, 255, 255, 255)
        PixelEffects.gaussianBlur(pixels, width, height, sigma = 3f)
        val center = extractRed(pixels[20 * width + 20])
        assertTrue("le pic doit être atténué", center in 1 until 255)
        assertEquals(extractRed(pixels[20 * width + 17]), extractRed(pixels[20 * width + 23]))
        assertEquals(extractRed(pixels[17 * width + 20]), extractRed(pixels[23 * width + 20]))
        assertTrue(extractRed(pixels[20 * width + 21]) <= center)
        assertTrue(pixels.all { it ushr 24 == 255 })
    }
    @Test
    fun `gaussian blur keeps a non-square image in place`() {
        val width = 41
        val height = 25
        val pixels = IntArray(width * height) { packArgb(255, 0, 0, 0) }
        pixels[12 * width + 30] = packArgb(255, 255, 255, 255)
        PixelEffects.gaussianBlur(pixels, width, height, sigma = 2f)
        val peak = pixels.indices.maxBy { extractRed(pixels[it]) }
        assertEquals(12 * width + 30, peak)
        assertEquals(extractRed(pixels[12 * width + 27]), extractRed(pixels[12 * width + 33]))
        assertEquals(extractRed(pixels[9 * width + 30]), extractRed(pixels[15 * width + 30]))
    }
    @Test
    fun `gaussian blur actually removes detail from a checkerboard`() {
        val width = 32
        val height = 32
        val pixels = IntArray(width * height) { index -> if ((index % width + index / width) % 2 == 0) packArgb(255, 255, 255, 255) else packArgb(255, 0, 0, 0) }
        PixelEffects.gaussianBlur(pixels, width, height, sigma = 4f)
        val redValues = (8 until 24).flatMap { row ->
            (8 until 24).map { column -> extractRed(pixels[row * width + column]) }
        }
        assertTrue(
            "contraste résiduel ${redValues.max() - redValues.min()}",
            redValues.max() - redValues.min() < 16,
        )
    }
    @Test
    fun `box sizes approximate the requested gaussian variance`() {
        for ((sigma, tolerance) in listOf(2f to 0.2, 5f to 0.1, 12.5f to 0.05, 40f to 0.02)) {
            val sizes = PixelEffects.boxSizesForGauss(sigma, 3)
            assertTrue(sizes.all { it % 2 == 1 })
            val variance = sizes.sumOf { (it * it - 1) / 12.0 }
            val expected = sigma.toDouble() * sigma
            assertTrue("sigma=$sigma variance=$variance", abs(variance - expected) / expected < tolerance)
        }
    }
    @Test
    fun `downsample averages each block`() {
        val pixels = intArrayOf(
            packArgb(255, 0, 0, 0), packArgb(255, 100, 0, 0), packArgb(255, 40, 0, 0), packArgb(255, 40, 0, 0),
            packArgb(255, 200, 0, 0), packArgb(255, 100, 0, 0), packArgb(255, 40, 0, 0), packArgb(255, 40, 0, 0),
        )
        val downsampled = PixelEffects.downsample(pixels, 4, 2, factor = 2)
        assertArrayEquals(intArrayOf(packArgb(255, 100, 0, 0), packArgb(255, 40, 0, 0)), downsampled)
    }
    @Test
    fun `downsample handles partial blocks at the right and bottom edges`() {
        val pixels = IntArray(5 * 3) { index -> packArgb(255, index * 10, 0, 0) }
        val downsampled = PixelEffects.downsample(pixels, 5, 3, factor = 4)
        assertEquals(2, downsampled.size)
        assertEquals(90, extractRed(downsampled[1]))
        assertEquals(PixelEffects.reducedSize(5, 4), 2)
        assertEquals(PixelEffects.reducedSize(4, 4), 1)
    }
    @Test
    fun `gaussian downscale keeps a working sigma of a few pixels`() {
        assertEquals(1, PixelEffects.gaussianDownscale(3f))
        for (sigma in listOf(4f, 24f, 120f)) {
            val workingSigma = sigma / PixelEffects.gaussianDownscale(sigma)
            assertTrue("sigma=$sigma working=$workingSigma", workingSigma >= 4f && workingSigma < 8f)
        }
    }
    @Test
    fun `sigma and tile scale with the face and stay within bounds`() {
        assertEquals(1f, PixelEffects.gaussianSigma(10f, MIN_BLUR_STRENGTH), 0f)
        assertEquals(24f, PixelEffects.gaussianSigma(200f, 6), 1e-4f)
        assertEquals(
            PixelEffects.gaussianSigma(200f, MAX_BLUR_STRENGTH),
            PixelEffects.gaussianSigma(200f, MAX_BLUR_STRENGTH + 5),
            0f,
        )
        assertEquals(20, PixelEffects.mosaicTile(200f, 10))
        assertEquals(2, PixelEffects.mosaicTile(5f, MIN_TILE_PERCENT))
        assertEquals(100, PixelEffects.mosaicTile(200f, 99))
    }
}
