package org.example.faceblur.data

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.example.faceblur.domain.BlurSettings
import org.example.faceblur.domain.BlurShape
import org.example.faceblur.domain.BlurType
import org.example.faceblur.domain.FaceBox
import org.example.faceblur.domain.FaceRegion
import org.example.faceblur.domain.PixelEffects
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Produit l'image exportée (et son aperçu, identique) à partir des zones à flouter. */
object FaceRenderer {

    /**
     * Image finale : zones [FaceRegion.blurred] floutées selon [settings], bbox dessinées si [drawBoxes].
     * Annulable entre deux zones (aperçu recalculé à chaque réglage).
     */
    suspend fun render(src: Bitmap, regions: List<FaceRegion>, settings: BlurSettings, drawBoxes: Boolean): Bitmap {
        val targets = regions.filter { it.blurred }
        if (targets.isEmpty() || (!settings.enabled && !drawBoxes)) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        if (settings.enabled) {
            val canvas = Canvas(out)
            for (region in targets) {
                currentCoroutineContext().ensureActive()
                blurRegion(out, canvas, region, settings)
            }
        }
        if (drawBoxes) drawBoxes(out, targets)
        return out
    }

    /**
     * Visage détecté : zone élargie de 15 % par côté en rectangle, 21 % ((√2 − 1)/2) en ovale
     * pour que l'ellipse englobe toute la bbox. Zone manuelle : prise telle que tracée.
     */
    private fun blurRegion(out: Bitmap, canvas: Canvas, region: FaceRegion, settings: BlurSettings) {
        val pad = when {
            region.isManual -> 0f
            settings.shape == BlurShape.OVAL -> 0.21f
            else -> 0.15f
        }
        val zone = region.box.expanded(pad)
        val l = zone.left.roundToInt().coerceIn(0, out.width - 1)
        val t = zone.top.roundToInt().coerceIn(0, out.height - 1)
        val r = zone.right.roundToInt().coerceIn(l + 1, out.width)
        val b = zone.bottom.roundToInt().coerceIn(t + 1, out.height)
        val w = r - l
        val h = b - t
        val pixels = IntArray(w * h)
        out.getPixels(pixels, 0, w, l, t, w, h)

        val faceSize = max(1f, min(region.box.width, region.box.height))
        when (settings.type) {
            BlurType.GAUSSIAN ->
                PixelEffects.gaussianBlur(pixels, w, h, PixelEffects.gaussianSigma(faceSize, settings.strength))
            BlurType.MOSAIC ->
                PixelEffects.pixelate(pixels, w, h, PixelEffects.mosaicTile(faceSize, settings.tilePercent))
        }

        when (settings.shape) {
            BlurShape.RECT -> out.setPixels(pixels, 0, w, l, t, w, h)
            BlurShape.OVAL -> {
                // Ellipse sur la zone non rognée : le shader CLAMP prolonge les bords hors de l'image.
                val blurred = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                val shader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setTranslate(l.toFloat(), t.toFloat()) })
                }
                canvas.drawOval(zone.toRectF(), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
                blurred.recycle()
            }
        }
    }

    /** Bbox et score (visages détectés) dessinés sur [out]. */
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
            region.score?.let { canvas.drawText("%.2f".format(it), region.box.left, region.box.top - stroke, textPaint) }
        }
    }

    private fun FaceBox.toRectF() = RectF(left, top, right, bottom)
}
