package org.example.faceblur

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class Face(val box: RectF, val score: Float, val kps: List<Pair<Float, Float>>)

/**
 * Détecteur de visages SCRFD-500M (det_500m.onnx du pack buffalo_s d'InsightFace).
 * Port de insightface/python-package/insightface/model_zoo/scrfd.py.
 */
class ScrfdDetector(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession =
        env.createSession(context.assets.open("det_500m.onnx").use { it.readBytes() }, OrtSession.SessionOptions())
    private val inputName = session.inputNames.first()

    /** Toutes les détections au-dessus de [minScore], avant NMS. */
    fun detectCandidates(bitmap: Bitmap, minScore: Float = 0.2f): List<Face> {
        // Redimensionnement en conservant le ratio, collé en haut à gauche d'un carré noir.
        val ratio = bitmap.height.toFloat() / bitmap.width
        val (newW, newH) =
            if (ratio > 1f) (INPUT / ratio).toInt() to INPUT
            else INPUT to (INPUT * ratio).toInt()
        val detScale = newH.toFloat() / bitmap.height

        val padded = Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888)
        Canvas(padded).apply {
            drawColor(Color.BLACK)
            drawBitmap(Bitmap.createScaledBitmap(bitmap, newW, newH, true), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val pixels = IntArray(INPUT * INPUT)
        padded.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        padded.recycle()

        // NCHW, RGB, (x - 127.5) / 128
        val area = INPUT * INPUT
        val input = FloatBuffer.allocate(3 * area)
        for (i in 0 until area) {
            val p = pixels[i]
            input.put(i, (((p shr 16) and 0xFF) - 127.5f) / 128f)
            input.put(area + i, (((p shr 8) and 0xFF) - 127.5f) / 128f)
            input.put(2 * area + i, ((p and 0xFF) - 127.5f) / 128f)
        }

        val faces = ArrayList<Face>()
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { out ->
                for ((idx, stride) in STRIDES.withIndex()) {
                    @Suppress("UNCHECKED_CAST")
                    val scores = out[idx].value as Array<FloatArray>
                    @Suppress("UNCHECKED_CAST")
                    val boxes = out[idx + STRIDES.size].value as Array<FloatArray>
                    @Suppress("UNCHECKED_CAST")
                    val kps = out[idx + 2 * STRIDES.size].value as Array<FloatArray>
                    val gridW = INPUT / stride
                    for (a in scores.indices) {
                        val score = scores[a][0]
                        if (score < minScore) continue
                        // NUM_ANCHORS ancres consécutives par cellule, cellules en ordre ligne par ligne.
                        val cell = a / NUM_ANCHORS
                        val cx = (cell % gridW) * stride.toFloat()
                        val cy = (cell / gridW) * stride.toFloat()
                        val d = boxes[a]
                        val box = RectF(
                            (cx - d[0] * stride) / detScale,
                            (cy - d[1] * stride) / detScale,
                            (cx + d[2] * stride) / detScale,
                            (cy + d[3] * stride) / detScale,
                        )
                        val k = kps[a]
                        val points = (0 until 5).map { j ->
                            (cx + k[2 * j] * stride) / detScale to (cy + k[2 * j + 1] * stride) / detScale
                        }
                        faces += Face(box, score, points)
                    }
                }
            }
        }
        return faces
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val INPUT = 640
        private val STRIDES = intArrayOf(8, 16, 32)
        private const val NUM_ANCHORS = 2

        /** Filtre par score puis NMS glouton (IoU 0.4 comme InsightFace). */
        fun nms(candidates: List<Face>, scoreThresh: Float, iouThresh: Float = 0.4f): List<Face> {
            val sorted = candidates.filter { it.score >= scoreThresh }.sortedByDescending { it.score }
            val kept = ArrayList<Face>()
            for (f in sorted) {
                if (kept.none { iou(it.box, f.box) > iouThresh }) kept += f
            }
            return kept
        }

        // Même convention que la NMS d'InsightFace (+1 sur les dimensions).
        private fun iou(a: RectF, b: RectF): Float {
            val w = max(0f, min(a.right, b.right) - max(a.left, b.left) + 1)
            val h = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top) + 1)
            val inter = w * h
            val areaA = (a.width() + 1) * (a.height() + 1)
            val areaB = (b.width() + 1) * (b.height() + 1)
            return inter / (areaA + areaB - inter)
        }
    }
}

/** Floute chaque visage (zone élargie de 15 %) par sous-échantillonnage + suréchantillonnage filtré. */
fun blurFaces(src: Bitmap, faces: List<Face>): Bitmap {
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(out)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    for (f in faces) {
        val padX = f.box.width() * 0.15f
        val padY = f.box.height() * 0.15f
        val l = (f.box.left - padX).roundToInt().coerceIn(0, out.width - 1)
        val t = (f.box.top - padY).roundToInt().coerceIn(0, out.height - 1)
        val r = (f.box.right + padX).roundToInt().coerceIn(l + 1, out.width)
        val b = (f.box.bottom + padY).roundToInt().coerceIn(t + 1, out.height)
        val w = r - l
        val h = b - t
        val region = Bitmap.createBitmap(out, l, t, w, h)
        // Deux passes de réduction pour un flou plus doux qu'une simple pixellisation.
        var small = region
        for (factor in intArrayOf(4, 4)) {
            small = Bitmap.createScaledBitmap(small, max(1, small.width / factor), max(1, small.height / factor), true)
        }
        val blurred = Bitmap.createScaledBitmap(small, w, h, true)
        canvas.drawBitmap(blurred, l.toFloat(), t.toFloat(), paint)
    }
    return out
}

/** Copie de [src] avec les bbox et leur score dessinés. */
fun drawFaces(src: Bitmap, faces: List<Face>): Bitmap {
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
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
    for (f in faces) {
        canvas.drawRect(f.box, boxPaint)
        canvas.drawText("%.2f".format(f.score), f.box.left, f.box.top - stroke, textPaint)
    }
    return out
}

/** Image finale : floutée et/ou avec bbox, selon les options. */
fun render(src: Bitmap, faces: List<Face>, blur: Boolean, boxes: Boolean): Bitmap {
    var out = if (blur) blurFaces(src, faces) else src
    if (boxes) out = drawFaces(out, faces)
    return out
}
