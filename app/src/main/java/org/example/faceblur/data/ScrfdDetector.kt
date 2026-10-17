package org.example.faceblur.data

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.example.faceblur.FaceDetectionLog
import org.example.faceblur.domain.Face
import org.example.faceblur.domain.FaceBox
import java.nio.FloatBuffer

/**
 * Détecteur de visages SCRFD-500M (det_500m.onnx du pack buffalo_s d'InsightFace).
 * Port de insightface/python-package/insightface/model_zoo/scrfd.py.
 */
class ScrfdDetector(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = FaceDetectionLog.timed(TAG, "Loading det_500m.onnx") {
        env.createSession(context.assets.open("det_500m.onnx").use { it.readBytes() }, OrtSession.SessionOptions())
    }
    private val inputName = session.inputNames.first()

    /** Toutes les détections au-dessus de [minScore], avant NMS (voir nmsIndices). */
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
            val out = FaceDetectionLog.timed(TAG, "SCRFD inference") { session.run(mapOf(inputName to tensor)) }
            out.use {
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
                        val box = FaceBox(
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
        FaceDetectionLog.d(TAG) { "${faces.size} candidates >= $minScore on ${bitmap.width}x${bitmap.height}" }
        return faces
    }

    override fun close() {
        session.close()
    }

    private companion object {
        const val TAG = "Detector"
        const val INPUT = 640
        val STRIDES = intArrayOf(8, 16, 32)
        const val NUM_ANCHORS = 2
    }
}
