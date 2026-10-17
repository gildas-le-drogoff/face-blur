// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.domain

import kotlin.math.*

object PixelEffects {
    const val SIGMA_PER_STRENGTH = 0.02f
    fun gaussianSigma(faceSize: Float, strength: Int): Float =
        max(
            1f,
            faceSize * strength.coerceIn(MIN_BLUR_STRENGTH, MAX_BLUR_STRENGTH) * SIGMA_PER_STRENGTH
        )

    fun mosaicTile(faceSize: Float, tilePercent: Int): Int =
        max(
            2,
            (faceSize * tilePercent.coerceIn(
                MIN_TILE_PERCENT,
                MAX_TILE_PERCENT
            ) / 100f).roundToInt()
        )

    const val GAUSSIAN_WORKING_SIGMA = 4f
    fun gaussianDownscale(sigma: Float): Int = max(1, (sigma / GAUSSIAN_WORKING_SIGMA).toInt())
    fun gaussianBlur(pixels: IntArray, width: Int, height: Int, sigma: Float) {
        require(pixels.size >= width * height)
        if (width == 0 || height == 0) return
        val tmp = IntArray(width * height)
        for (size in boxSizesForGauss(sigma, 3)) {
            val radius = (size - 1) / 2
            boxBlurTransposed(pixels, tmp, width, height, radius)
            boxBlurTransposed(tmp, pixels, height, width, radius)
        }
    }

    fun reducedSize(size: Int, factor: Int): Int = (size + factor - 1) / factor
    fun downsample(pixels: IntArray, width: Int, height: Int, factor: Int): IntArray {
        require(factor >= 1 && pixels.size >= width * height)
        if (factor == 1) return pixels.copyOf(width * height)
        val reducedWidth = reducedSize(width, factor)
        val out = IntArray(reducedWidth * reducedSize(height, factor))
        for (ty in 0 until height step factor) {
            val yEnd = min(ty + factor, height)
            for (tx in 0 until width step factor) {
                val xEnd = min(tx + factor, width)
                var a = 0L
                var r = 0L
                var g = 0L
                var b = 0L
                for (y in ty until yEnd) for (x in tx until xEnd) {
                    val p = pixels[y * width + x]
                    a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
                }
                val n = ((yEnd - ty) * (xEnd - tx)).toLong()
                out[ty / factor * reducedWidth + tx / factor] =
                    argb(roundDiv(a, n), roundDiv(r, n), roundDiv(g, n), roundDiv(b, n))
            }
        }
        return out
    }

    internal fun boxSizesForGauss(sigma: Float, boxCount: Int): IntArray {
        val s2 = sigma.toDouble() * sigma
        val wIdeal = sqrt(12 * s2 / boxCount + 1)
        var wl = floor(wIdeal).toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal =
            (12 * s2 - boxCount * wl * wl - 4 * boxCount * wl - 3 * boxCount) / (-4 * wl - 4)
        val m = mIdeal.roundToInt()
        return IntArray(boxCount) { if (it < m) wl else wu }
    }

    private fun boxBlurTransposed(
        src: IntArray,
        dst: IntArray,
        width: Int,
        height: Int,
        radius: Int
    ) {
        val div = 2 * radius + 1
        for (y in 0 until height) {
            val row = y * width
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -radius..radius) {
                val p = src[row + i.coerceIn(0, width - 1)]
                sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            for (x in 0 until width) {
                dst[x * height + y] =
                    argb(roundDiv(sa, div), roundDiv(sr, div), roundDiv(sg, div), roundDiv(sb, div))
                val add = src[row + min(x + radius + 1, width - 1)]
                val rem = src[row + max(x - radius, 0)]
                sa += (add ushr 24) - (rem ushr 24)
                sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                sb += (add and 0xFF) - (rem and 0xFF)
            }
        }
    }

    private fun roundDiv(sum: Int, div: Int): Int = (sum + div / 2) / div
    private fun roundDiv(sum: Long, div: Long): Int = ((sum + div / 2) / div).toInt()
    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b
}
