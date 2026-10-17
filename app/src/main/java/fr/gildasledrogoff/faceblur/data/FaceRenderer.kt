// SPDX-FileCopyrightText: 2026 Gildas Le Drogoff and FaceBlur contributors
//
// SPDX-License-Identifier: AGPL-3.0-or-later

package fr.gildasledrogoff.faceblur.data

import android.graphics.*
import fr.gildasledrogoff.faceblur.FaceDetectionLog
import fr.gildasledrogoff.faceblur.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object FaceRenderer {
    suspend fun render(
        src: Bitmap,
        regions: List<FaceRegion>,
        settings: BlurSettings,
        drawBoxes: Boolean
    ): Bitmap {
        val targets = regions.filter { it.blurred }
        if (targets.isEmpty()) return src
        val out = FaceDetectionLog.timed(TAG, "Copying source ${src.width}x${src.height}") {
            src.copy(Bitmap.Config.ARGB_8888, true)
        }
        val canvas = Canvas(out)
        for (region in targets) {
            currentCoroutineContext().ensureActive()
            blurRegion(out, canvas, region, settings)
        }
        if (drawBoxes) drawBoxes(out, targets)
        return out
    }

    private fun blurRegion(
        out: Bitmap,
        canvas: Canvas,
        region: FaceRegion,
        settings: BlurSettings
    ) {
        val zone = region.box.expanded(if (region.isManual) 0f else 0.21f)
        val left = zone.left.roundToInt().coerceIn(0, out.width - 1)
        val top = zone.top.roundToInt().coerceIn(0, out.height - 1)
        val right = zone.right.roundToInt().coerceIn(left + 1, out.width)
        val bottom = zone.bottom.roundToInt().coerceIn(top + 1, out.height)
        val width = right - left
        val height = bottom - top
        val pixels = IntArray(width * height)
        out.getPixels(pixels, 0, width, left, top, width, height)
        val faceSize = max(1f, min(region.box.width, region.box.height))
        val sigma = PixelEffects.gaussianSigma(faceSize, settings.strength)
        val factor = when (settings.type) {
            BlurType.GAUSSIAN -> PixelEffects.gaussianDownscale(sigma)
            BlurType.MOSAIC -> PixelEffects.mosaicTile(faceSize, settings.tilePercent)
        }
        val reducedWidth = PixelEffects.reducedSize(width, factor)
        val reducedHeight = PixelEffects.reducedSize(height, factor)
        val downsampled = FaceDetectionLog.timed(
            TAG,
            "Computing ${settings.type} on zone ${width}x$height (factor $factor -> ${reducedWidth}x${reducedHeight})"
        ) {
            PixelEffects.downsample(pixels, width, height, factor).also {
                if (settings.type == BlurType.GAUSSIAN) {
                    PixelEffects.gaussianBlur(it, reducedWidth, reducedHeight, sigma / factor)
                }
            }
        }
        val reduced =
            Bitmap.createBitmap(downsampled, reducedWidth, reducedHeight, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(reduced, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setScale(factor.toFloat(), factor.toFloat())
                postTranslate(left.toFloat(), top.toFloat())
            })
        }
        canvas.drawOval(zone.toRectF(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.shader = shader
            isFilterBitmap = settings.type == BlurType.GAUSSIAN
        })
        reduced.recycle()
    }

    private fun drawBoxes(out: Bitmap, regions: List<FaceRegion>) {
        val canvas = Canvas(out)
        val stroke = max(2f, max(out.width, out.height) / 300f)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = Color.GREEN
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            textSize = stroke * 8
        }
        for (region in regions) {
            canvas.drawRect(region.box.toRectF(), boxPaint)
            region.score?.let {
                canvas.drawText(
                    "%.2f".format(it),
                    region.box.left,
                    region.box.top - stroke,
                    textPaint
                )
            }
        }
    }

    private fun FaceBox.toRectF() = RectF(left, top, right, bottom)
    private const val TAG = "Renderer"
}
