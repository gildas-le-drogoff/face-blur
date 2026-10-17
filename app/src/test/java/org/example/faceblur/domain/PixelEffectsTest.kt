package org.example.faceblur.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PixelEffectsTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(p: Int) = (p shr 16) and 0xFF

    @Test
    fun `gaussian blur leaves a uniform image unchanged`() {
        val color = argb(255, 10, 120, 240)
        val pixels = IntArray(30 * 20) { color }
        PixelEffects.gaussianBlur(pixels, 30, 20, sigma = 5f)
        assertTrue(pixels.all { it == color })
    }

    @Test
    fun `gaussian blur spreads an impulse symmetrically and keeps opacity`() {
        val w = 41
        val h = 41
        val pixels = IntArray(w * h) { argb(255, 0, 0, 0) }
        pixels[20 * w + 20] = argb(255, 255, 255, 255)
        PixelEffects.gaussianBlur(pixels, w, h, sigma = 3f)

        val center = red(pixels[20 * w + 20])
        assertTrue("le pic doit être atténué", center in 1 until 255)
        assertEquals(red(pixels[20 * w + 17]), red(pixels[20 * w + 23]))
        assertEquals(red(pixels[17 * w + 20]), red(pixels[23 * w + 20]))
        assertTrue(red(pixels[20 * w + 21]) <= center)
        assertTrue(pixels.all { it ushr 24 == 255 })
    }

    @Test
    fun `gaussian blur actually removes detail from a checkerboard`() {
        val w = 32
        val h = 32
        val pixels = IntArray(w * h) { i -> if ((i % w + i / w) % 2 == 0) argb(255, 255, 255, 255) else argb(255, 0, 0, 0) }
        PixelEffects.gaussianBlur(pixels, w, h, sigma = 4f)
        // Intérieur seulement : les bords répliqués biaisent légèrement la moyenne.
        val reds = (8 until 24).flatMap { y -> (8 until 24).map { x -> red(pixels[y * w + x]) } }
        assertTrue("contraste résiduel ${reds.max() - reds.min()}", reds.max() - reds.min() < 16)
    }

    @Test
    fun `box sizes approximate the requested gaussian variance`() {
        // Les largeurs entières limitent la précision à petit sigma ; l'erreur diminue ensuite.
        for ((sigma, tolerance) in listOf(2f to 0.2, 5f to 0.1, 12.5f to 0.05, 40f to 0.02)) {
            val sizes = PixelEffects.boxSizesForGauss(sigma, 3)
            assertTrue(sizes.all { it % 2 == 1 })
            // Variance d'un flou boîte de largeur w : (w² − 1) / 12.
            val variance = sizes.sumOf { (it * it - 1) / 12.0 }
            val expected = sigma.toDouble() * sigma
            assertTrue("sigma=$sigma variance=$variance", abs(variance - expected) / expected < tolerance)
        }
    }

    @Test
    fun `pixelate fills each tile with its average colour`() {
        // 4×2, carreaux de 2 : [0, 100 / 200, 100] → moyenne 100 ; [40, 40 / 40, 40] → 40.
        val pixels = intArrayOf(
            argb(255, 0, 0, 0), argb(255, 100, 0, 0), argb(255, 40, 0, 0), argb(255, 40, 0, 0),
            argb(255, 200, 0, 0), argb(255, 100, 0, 0), argb(255, 40, 0, 0), argb(255, 40, 0, 0),
        )
        PixelEffects.pixelate(pixels, 4, 2, tile = 2)
        val expected = intArrayOf(100, 100, 40, 40, 100, 100, 40, 40).map { argb(255, it, 0, 0) }.toIntArray()
        assertArrayEquals(expected, pixels)
    }

    @Test
    fun `pixelate handles partial tiles at the right and bottom edges`() {
        val pixels = IntArray(5 * 3) { i -> argb(255, i * 10, 0, 0) }
        PixelEffects.pixelate(pixels, 5, 3, tile = 4)
        // Carreau partiel en bas à droite : colonne 4, lignes 0..2 → valeurs 40, 90, 140.
        assertEquals(90, red(pixels[4]))
        assertEquals(red(pixels[4]), red(pixels[2 * 5 + 4]))
        // Carreau principal 4×3 : identique partout.
        assertTrue((0 until 3).all { y -> (0 until 4).all { x -> pixels[y * 5 + x] == pixels[0] } })
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
