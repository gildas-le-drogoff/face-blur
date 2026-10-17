package org.example.faceblur.domain

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Effets d'anonymisation sur des pixels ARGB 32 bits (format de Bitmap.getPixels),
 * modifiés en place. Kotlin pur : testable sans Android.
 */
object PixelEffects {

    /** Écart-type du flou gaussien par cran de force, en fraction du plus petit côté du visage. */
    const val SIGMA_PER_STRENGTH = 0.02f

    fun gaussianSigma(faceSize: Float, strength: Int): Float =
        max(1f, faceSize * strength.coerceIn(MIN_BLUR_STRENGTH, MAX_BLUR_STRENGTH) * SIGMA_PER_STRENGTH)

    fun mosaicTile(faceSize: Float, tilePercent: Int): Int =
        max(2, (faceSize * tilePercent.coerceIn(MIN_TILE_PERCENT, MAX_TILE_PERCENT) / 100f).roundToInt())

    /**
     * Flou gaussien d'écart-type [sigma] approché par trois flous boîte successifs
     * (horizontal puis vertical), bords répliqués. Coût indépendant de [sigma].
     */
    fun gaussianBlur(pixels: IntArray, width: Int, height: Int, sigma: Float) {
        require(pixels.size >= width * height)
        if (width == 0 || height == 0) return
        val tmp = IntArray(width * height)
        for (size in boxSizesForGauss(sigma, 3)) {
            val r = (size - 1) / 2
            boxBlurH(pixels, tmp, width, height, r)
            boxBlurV(tmp, pixels, width, height, r)
        }
    }

    /** Mosaïque : chaque carreau [tile]×[tile] (alignés en haut à gauche) prend sa couleur moyenne. */
    fun pixelate(pixels: IntArray, width: Int, height: Int, tile: Int) {
        require(tile >= 1 && pixels.size >= width * height)
        for (ty in 0 until height step tile) {
            val yEnd = min(ty + tile, height)
            for (tx in 0 until width step tile) {
                val xEnd = min(tx + tile, width)
                var a = 0L; var r = 0L; var g = 0L; var b = 0L
                for (y in ty until yEnd) for (x in tx until xEnd) {
                    val p = pixels[y * width + x]
                    a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
                }
                val n = ((yEnd - ty) * (xEnd - tx)).toLong()
                val avg = argb(roundDiv(a, n), roundDiv(r, n), roundDiv(g, n), roundDiv(b, n))
                for (y in ty until yEnd) pixels.fill(avg, y * width + tx, y * width + xEnd)
            }
        }
    }

    /**
     * Largeurs (impaires) de [n] flous boîte dont la composition approche une gaussienne
     * d'écart-type [sigma] (I. Kutskir, « Fastest Gaussian Blur »).
     */
    internal fun boxSizesForGauss(sigma: Float, n: Int): IntArray {
        val s2 = sigma.toDouble() * sigma
        val wIdeal = sqrt(12 * s2 / n + 1)
        var wl = floor(wIdeal).toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal = (12 * s2 - n * wl * wl - 4 * n * wl - 3 * n) / (-4 * wl - 4)
        val m = mIdeal.roundToInt()
        return IntArray(n) { if (it < m) wl else wu }
    }

    private fun boxBlurH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        if (r <= 0) { src.copyInto(dst, 0, 0, w * h); return }
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = argb(roundDiv(sa, div), roundDiv(sr, div), roundDiv(sg, div), roundDiv(sb, div))
                val add = src[row + min(x + r + 1, w - 1)]
                val rem = src[row + max(x - r, 0)]
                sa += (add ushr 24) - (rem ushr 24)
                sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                sb += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }

    private fun boxBlurV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        if (r <= 0) { src.copyInto(dst, 0, 0, w * h); return }
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = argb(roundDiv(sa, div), roundDiv(sr, div), roundDiv(sg, div), roundDiv(sb, div))
                val add = src[min(y + r + 1, h - 1) * w + x]
                val rem = src[max(y - r, 0) * w + x]
                sa += (add ushr 24) - (rem ushr 24)
                sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                sb += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }

    private fun roundDiv(sum: Int, div: Int): Int = (sum + div / 2) / div
    private fun roundDiv(sum: Long, div: Long): Int = ((sum + div / 2) / div).toInt()
    private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
}
